// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.service

import lucuma.odb.api.service.ErrorFormatter.syntax._
import _root_.fs2.concurrent.Queue
import cats.MonadError
import cats.data.ValidatedNel
import cats.effect.ConcurrentEffect
import cats.implicits._
import clue.model.StreamingMessage.{FromClient, FromServer}
import clue.model.json._
import io.circe._
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame.{Close, Text}
import org.http4s.{Header, Headers, HttpRoutes, InvalidMessageBodyFailure, ParseFailure, QueryParamDecoder, Request, Response}
import org.log4s.getLogger
import sangria.ast.Document
import sangria.parser.QueryParser


object Routes {

  private[this] val logger = getLogger

  def forService[F[_]](
    service: OdbService[F]
  )(
    implicit F: ConcurrentEffect[F], M: MonadError[F, Throwable]
  ): HttpRoutes[F] = {

    def info(m: String): F[Unit] =
      F.delay(logger.info(m))

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

    val webSocketConnection: F[Response[F]] =
      for {
        replyQueue <- Queue.noneTerminated[F, FromServer]
        connection <- Connection(service, replyQueue)
        response   <- WebSocketBuilder[F].build(

          // Replies to client
          replyQueue
            .dequeue
            .map(_.asJson.spaces2)
            .evalTap(m => info(s"Sending to client $m"))
            .map(Text(_)),

          // Input from client
          _.evalTap(f => info(s"Received message from client: $f"))
            .evalMap {
              case Text(s, _) =>
                scala.util.Try(parser.decode[FromClient](s)).toEither.flatten.fold(
                  e => M.raiseError[Unit](new RuntimeException(s"Could not parse client message $s as FromClient: $e")),
                  m => connection.receive(m)
                )

              case Close(_)   =>
                connection.receive(FromClient.ConnectionTerminate)

              case f          =>
                M.raiseError[Unit](new RuntimeException(s"Expected a Text WebSocketFrame from Client, but got $f"))
            },

          Headers.of(Header("Sec-WebSocket-Protocol", "graphql-ws"))
        )
      } yield response


    HttpRoutes.of[F] {

      // GraphQL query is embedded in the URI query string when queried via GET
      case GET -> Root / "odb" :?  QueryMatcher(query) +& OperationNameMatcher(op) +& VariablesMatcher(vars) =>
        for {
          _ <- info(s"GET one off: query=$query, op=$op, vars=$vars")
          r <- oneOffGet(query, op, vars)
        } yield r

      // GraphQL query is embedded in a Json request body when queried via POST
      case req @ POST -> Root / "odb" =>
        for {
          _ <- info(s"POST one off: request=$req")
          r <- oneOffPost(req)
        } yield r

      // WebSocket connection request.
      case req @ GET -> Root / "ws" =>
        for {
          _ <- info(s"GET web socket: $req")
          r <- webSocketConnection
        } yield r

    }
  }

}