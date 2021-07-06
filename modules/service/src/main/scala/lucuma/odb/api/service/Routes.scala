// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.service

import lucuma.odb.api.service.ErrorFormatter.syntax._
import lucuma.core.model.User
import lucuma.sso.client.SsoClient

import cats.data.ValidatedNel
import cats.effect.Async
import cats.effect.std.Queue
import cats.implicits._
import clue.model.StreamingMessage.{FromClient, FromServer}
import clue.model.json._
import fs2.Stream
import org.typelevel.log4cats.Logger
import io.circe._
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.{Close, Text}
import org.http4s.{Header, Headers, HttpRoutes, InvalidMessageBodyFailure, ParseFailure, QueryParamDecoder, Request, Response}
import org.typelevel.ci.CIString
import sangria.ast.Document
import sangria.parser.QueryParser

import scala.concurrent.duration._


object Routes {

  val KeepAliveDuration: FiniteDuration =
    5.seconds

  def forService[F[_]: Logger: Async](
    service:    OdbService[F],
    userClient: SsoClient[F, User]
  ): HttpRoutes[F] = {

    val dsl = new Http4sDsl[F]{}
    import dsl._

    implicit val jsonQPDecoder: QueryParamDecoder[Json] = QueryParamDecoder[String].emap { s =>
      parser.parse(s).leftMap { case ParsingFailure(msg, _) => ParseFailure("Invalid variables", msg) }
    }

    object QueryMatcher         extends QueryParamDecoderMatcher[String]("query")
    object OperationNameMatcher extends OptionalQueryParamDecoderMatcher[String]("operationName")
    object VariablesMatcher     extends OptionalValidatingQueryParamDecoderMatcher[Json]("variables")

    def toResponse(result: Either[Throwable, Json]): F[Response[F]] =
      result match {
        case Left(err)   => BadRequest(err.format)
        case Right(json) => Ok(json)
      }

    def parse(query: String): Either[Throwable, Document] =
      QueryParser.parse(query).toEither

    def oneOffGet(
      query: String,
      op:    Option[String],
      vars0: Option[ValidatedNel[ParseFailure, Json]]
    ): F[Response[F]] =
      vars0.sequence.fold(
        errors =>
          BadRequest(errors.map(_.sanitized).mkString_("", ",", "")),

        vars   =>
          parse(query) match {
            case Left(error) =>
              BadRequest(error.format)

            case Right(ast)  =>
              for {
                result <- service.query(ParsedGraphQLRequest(ast, op, vars))
                resp   <- toResponse(result)
              } yield resp
          }
        )

    def oneOffPost(req: Request[F]): F[Response[F]] =
      for {
        body   <- req.as[Json]
        obj    <- body.asObject.liftTo[F](InvalidMessageBodyFailure("Invalid GraphQL query"))
        query  <- obj("query").flatMap(_.asString).liftTo[F](InvalidMessageBodyFailure("Missing query field"))
        op     =  obj("operationName").flatMap(_.asString)
        vars   =  obj("variables")
        parsed = parse(query).map(ParsedGraphQLRequest(_, op, vars))
        result <- parsed.traverse(service.query).map(_.flatten)
        resp   <- toResponse(result)
      } yield resp

    val webSocketConnection: F[Response[F]] = {

      val keepAliveStream: Stream[F, FromServer] =
        Stream
          .constant[F, FromServer](FromServer.ConnectionKeepAlive)
          .metered(KeepAliveDuration)

      def logFromServer(user: F[Option[User]], msg: FromServer): F[Unit] =
        msg match {
          case FromServer.ConnectionKeepAlive => debug(user, s"Sending ConnectionKeepAlive")
          case _                              => info(user, s"Sending to client: ${trimmedMessage(msg)}")
        }

      def logWebSocketFrame(user: F[Option[User]], f: WebSocketFrame): F[Unit] = {

        // The connection_init message payload has authorization information
        // which should not be logged.
        val AuthRegEx    = """("Authorization":)\s*"[^"]*"""".r.unanchored
        val RedactedAuth = """$1 <REDACTED>"""

        f match {
          case Text(s, last) => info(user, s"Received Text frame (last=$last) from client: ${AuthRegEx.replaceFirstIn(s, RedactedAuth)}")
          case _             => info(user, s"Received message from client: $f")
        }
      }

      def trimmedMessage(m: FromServer): String = {
        val s = m.asJson.spaces2
        if (s.length > 516) s"${s.take(512)} ..." else s
      }

      for {
        replyQueue <- Queue.unbounded[F, Option[FromServer]]
        connection <- Connection(service, userClient, replyQueue)
        response   <- WebSocketBuilder[F].copy(
            headers = Headers(Header.Raw(CIString("Sec-WebSocket-Protocol"), "graphql-ws"))
          ).build(

            // Replies to client
            Stream
              .fromQueueNoneTerminated(replyQueue)
              .mergeHaltL(keepAliveStream)
              .evalTap(logFromServer(connection.user, _))
              .map(m => Text(m.asJson.spaces2)),

            // Input from client
            _.evalTap(logWebSocketFrame(connection.user, _))
             .evalMap {
               case Text(s, _) =>
                 scala.util.Try(parser.decode[FromClient](s)).toEither.flatten.fold(
                   e => Async[F].raiseError[Unit](new RuntimeException(s"Could not parse client message $s as FromClient: $e")),
                   m => connection.receive(m)
                 )

               case Close(_)   =>
                 connection.close

               case f          =>
                 Async[F].raiseError[Unit](new RuntimeException(s"Expected a Text WebSocketFrame from Client, but got $f"))
             }
          )
      } yield response
    }


    HttpRoutes.of[F] {

      // GraphQL query is embedded in the URI query string when queried via GET
      case req @ GET -> Root / "odb" :?  QueryMatcher(query) +& OperationNameMatcher(op) +& VariablesMatcher(vars) =>
        for {
          _ <- info(userClient.find(req), s"GET one off: query=$query, op=$op, vars=$vars")
          r <- oneOffGet(query, op, vars)
        } yield r

      // GraphQL query is embedded in a Json request body when queried via POST
      case req @ POST -> Root / "odb" =>
        for {
          _ <- info(userClient.find(req), s"POST one off: request=$req")
          r <- oneOffPost(req)
        } yield r

      // WebSocket connection request.
      case req @ GET -> Root / "ws" =>
        for {
          _ <- info(None, s"GET web socket: $req")
          r <- webSocketConnection
        } yield r

    }
  }

}