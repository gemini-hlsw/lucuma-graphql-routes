// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.data.Nested
import cats.data.ValidatedNel
import cats.effect._
import cats.effect.std.Queue
import cats.implicits._
import clue.model.StreamingMessage.FromClient
import clue.model.StreamingMessage.FromServer
import clue.model.json._
import edu.gemini.grackle.Result
import fs2.Stream
import io.circe._
import io.circe.syntax._
import org.http4s.Header
import org.http4s.Headers
import org.http4s.HttpRoutes
import org.http4s.InvalidMessageBodyFailure
import org.http4s.MediaType
import org.http4s.ParseFailure
import org.http4s.QueryParamDecoder
import org.http4s.Request
import org.http4s.Response
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Authorization
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Close
import org.http4s.websocket.WebSocketFrame.Text
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger

import scala.concurrent.duration._

object Routes {

  val KeepAliveDuration: FiniteDuration =
    5.seconds

  def forService[F[_]: Logger: Async](
    service:        Option[Authorization] => F[Option[GraphQLService[F]]],
    wsBuilder:      WebSocketBuilder2[F],
    graphQLPath:    String = "graphql",
    wsPath:         String = "ws",
    playgroundPath: String = "playground.html",
  ): HttpRoutes[F] = {

    val dsl = new Http4sDsl[F]{}
    import dsl._

    implicit val jsonQPDecoder: QueryParamDecoder[JsonObject] = QueryParamDecoder[String].emap { s =>
      parser.parse(s) match { 
        case Left(ParsingFailure(msg, _)) => Left(ParseFailure("Invalid variables", msg))
        case Right(json) => json.asObject.toRight(ParseFailure("Expected JsonObject", json.spaces2))
      }
    }

    object QueryMatcher         extends QueryParamDecoderMatcher[String]("query")
    object OperationNameMatcher extends OptionalQueryParamDecoderMatcher[String]("operationName")
    object VariablesMatcher     extends OptionalValidatingQueryParamDecoderMatcher[JsonObject]("variables")

    def handler(req: Request[F]): F[Option[HttpRouteHandler[F]]] =
      Nested(service(req.headers.get[Authorization])).map(new HttpRouteHandler(_)).value

    val playground: F[Response[F]] =
      Ok(Playground(graphQLPath, wsPath)).map(_.withContentType(`Content-Type`(MediaType.text.html)))

    HttpRoutes.of[F] {

      // GraphQL query is embedded in the URI query string when queried via GET
      case req @ GET -> Root / `graphQLPath` :?  QueryMatcher(query) +& OperationNameMatcher(op) +& VariablesMatcher(vars) =>
        Logger[F].debug(s"GET one off: query=$query, op=$op, vars=$vars") *>
        handler(req).flatMap {
          case Some(h) => h.oneOffGet(query, op, vars)
          case None    => Forbidden("Access denied.")
        }

      // GraphQL query is embedded in a Json request body when queried via POST
      case req @ POST -> Root / `graphQLPath` =>
        Logger[F].debug(s"POST one off: request=$req") *>
        handler(req).flatMap {
          case Some(h) => h.oneOffPost(req)
          case None    => Forbidden("Access denied.")
        }

      // WebSocket connection request.
      case req @ GET -> Root / `wsPath` =>
        Logger[F].debug(s"GET web socket: $req") *>
        new WsRouteHandler(service).webSocketConnection(wsBuilder)

      // GraphQL Playground
      case GET -> Root / `playgroundPath` =>
        playground

    }
  }

}

class HttpRouteHandler[F[_]: Temporal](service: GraphQLService[F]) {

  val dsl: Http4sDsl[F] = new Http4sDsl[F]{}
  import dsl._

  def toResponse(result: Result[Json]): F[Response[F]] =
    Ok(service.mapping.mkResponse(result))

  def oneOffGet(
    query: String,
    op:    Option[String],
    vars0: Option[ValidatedNel[ParseFailure, JsonObject]]
  ): F[Response[F]] =
    vars0.sequence.fold(
      errors => Ok(errors.map(_.sanitized).mkString_("", ",", "")), // in GraphQL errors are reported in a 200 Ok response (!)
      vars   => service.parse(query, op, vars).flatTraverse(service.query).flatMap(toResponse)
    )

  def oneOffPost(req: Request[F]): F[Response[F]] =
    for {
      body   <- req.as[Json]
      obj    <- body.asObject.liftTo[F](InvalidMessageBodyFailure("Invalid GraphQL query"))
      query  <- obj("query").flatMap(_.asString).liftTo[F](InvalidMessageBodyFailure("Missing query field"))
      op     =  obj("operationName").flatMap(_.asString)
      vars   =  obj("variables").flatMap(_.asObject)
      parsed =  service.parse(query, op, vars)
      result <- parsed.traverse(service.query).map(_.flatten)
      resp   <- toResponse(result)
    } yield resp

}

class WsRouteHandler[F[_]: Logger: Temporal](service: Option[Authorization] => F[Option[GraphQLService[F]]]) {

  val KeepAliveDuration: FiniteDuration =
    5.seconds

  def webSocketConnection(wsb: WebSocketBuilder2[F]): F[Response[F]] = {

    val keepAliveStream: Stream[F, FromServer] =
      Stream
        .constant[F, FromServer](FromServer.ConnectionKeepAlive)
        .metered(KeepAliveDuration)

    def logFromServer(msg: FromServer): F[Unit] =
      msg match {
        case FromServer.ConnectionKeepAlive => Logger[F].debug(s"Sending ConnectionKeepAlive")
        case _                              => Logger[F].debug(s"Sending to client: ${trimmedMessage(msg)}")
      }

    def logWebSocketFrame(f: WebSocketFrame): F[Unit] = {

      // The connection_init message payload has authorization information
      // which should not be logged.
      val AuthRegEx    = """("Authorization":)\s*"[^"]*"""".r.unanchored
      val RedactedAuth = """$1 <REDACTED>"""

      f match {
        case Text(s, last) => Logger[F].debug(s"Received Text frame (last=$last) from client: ${AuthRegEx.replaceFirstIn(s, RedactedAuth)}")
        case _             => Logger[F].debug(s"Received message from client: $f")
      }
    }

    def trimmedMessage(m: FromServer): String = {
      val s = m.asJson.spaces2
      if (s.length > 516) s"${s.take(512)} ..." else s
    }

    for {
      replyQueue <- Queue.unbounded[F, Option[FromServer]]
      connection <- Connection(service, replyQueue)
      response   <- wsb.withHeaders(Headers(Header.Raw(CIString("Sec-WebSocket-Protocol"), "graphql-ws"))).build(

          // Replies to client
          Stream
            .fromQueueNoneTerminated(replyQueue)
            .mergeHaltL(keepAliveStream)
            .evalTap(logFromServer)
            .map(m => Text(m.asJson.spaces2)),

          // Input from client
          _.evalTap(logWebSocketFrame)
            .evalMap {
              case Text(s, _) =>
                scala.util.Try(parser.decode[FromClient](s)).toEither.flatten.fold(
                  e => Concurrent[F].raiseError[Unit](new RuntimeException(s"Could not parse client message $s as FromClient: $e")),
                  m => connection.receive(m)
                )

              case Close(_)   =>
                connection.close

              case f          =>
                Concurrent[F].raiseError[Unit](new RuntimeException(s"Expected a Text WebSocketFrame from Client, but got $f"))
            }
        )
    } yield response
  }

}