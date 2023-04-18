// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.Async
import cats.effect.std.Dispatcher
import cats.syntax.all._
import clue.model.GraphQLErrors
import fs2.Stream
import io.circe._
import sangria.ast.OperationType
import sangria.execution.ExceptionHandler
import sangria.execution._
import sangria.marshalling.circe._
import sangria.parser.QueryParser
import sangria.schema.Schema
import sangria.streaming
import sangria.streaming.SubscriptionStream

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

class SangriaGraphQLService[F[_]: Async, A](
  schema:           Schema[A, Unit],
  userData:         A,
  exceptionHandler: ExceptionHandler
) extends GraphQLService[F] {

  type Document = sangria.ast.Document

  def parse(query: String, op: Option[String]): Either[Throwable, Document] =
    QueryParser.parse(query).toEither

  def isSubscription(req: ParsedGraphQLRequest): Boolean =
    req.query.operationType(req.op).contains(OperationType.Subscription)

  def query(request: ParsedGraphQLRequest): F[Either[Throwable, Json]] =
    Async[F].async_ { (cb: Either[Throwable, Json] => Unit) =>
      Executor.execute(
        schema           = schema,
        queryAst         = request.query,
        userContext      = userData,
        operationName    = request.op,
        variables        = request.vars.getOrElse(Json.fromJsonObject(JsonObject())),
        exceptionHandler = exceptionHandler
      ).onComplete {
        case Success(value) => cb(Right(value))
        case Failure(error) => cb(Left(error))
      }
    }.attempt

  def subscribe(
    request: ParsedGraphQLRequest
  ): Stream[F, Either[Throwable, Json]] = {

    implicit def subStream(implicit D: Dispatcher[F]): SubscriptionStream[Stream[F, *]] =
      streaming.fs2.fs2SubscriptionStream[F]

    import sangria.execution.ExecutionScheme.Stream

    fs2.Stream.eval {
      Dispatcher.parallel[F].use { implicit d =>
        Async[F].fromFuture {
          Async[F].delay {
            Executor.prepare(
              schema           = schema,
              queryAst         = request.query,
              userContext      = userData,
              operationName    = request.op,
              variables        = request.vars.getOrElse(Json.fromJsonObject(JsonObject())),
              exceptionHandler = exceptionHandler
            ).map { preparedQuery =>
              preparedQuery
                .execute()
                .map(_.asRight[Throwable])
                .recover { case NonFatal(error) => error.asLeft[Json] }
            }
          }
        }
      }
    } .flatten

  }

  def format(err: Throwable): F[GraphQLErrors] =
    ErrorConverter.toGraphQLError(err).pure[F]

}
