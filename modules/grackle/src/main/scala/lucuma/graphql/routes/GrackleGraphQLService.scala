// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadThrow
import cats.data.NonEmptyChain
import cats.data.NonEmptyList
import cats.syntax.all._
import clue.model.GraphQLError
import clue.model.GraphQLErrors
import edu.gemini.grackle.Cursor
import edu.gemini.grackle.Mapping
import edu.gemini.grackle.Problem
import edu.gemini.grackle.QueryParser
import edu.gemini.grackle.UntypedOperation
import edu.gemini.grackle.UntypedOperation.UntypedSubscription
import fs2.Compiler
import fs2.Stream
import io.circe.Json
import lucuma.graphql.routes.conversions._
import natchez.Trace
import org.typelevel.log4cats.Logger

import scala.util.control.NonFatal

class GrackleGraphQLService[F[_]: MonadThrow: Logger: Trace](
  mapping: Mapping[F],
)(implicit ev: Compiler[F,F]) extends GraphQLService[F] {

  type Document = UntypedOperation

  def isSubscription(req: ParsedGraphQLRequest): Boolean =
    req.query match {
      case UntypedSubscription(_, _) => true
      case _                         => false
    }

  def parse(query: String, op: Option[String]): Either[Throwable, Document] =
    QueryParser.parseText(query, op).toEither.leftMap(_.map(GrackleException(_)).merge)

  def query(request: ParsedGraphQLRequest): F[Either[Throwable, Json]] =
    Trace[F].span("graphql") {
      Trace[F].put("graphql.query" -> request.query.query.render) *>
      subscribe(request).compile.toList.map {
        case List(e) => e
        case other   => GrackleException(Problem(s"Expected exactly one result, found ${other.length}.")).asLeft
      }
    }

  def subscribe(request: ParsedGraphQLRequest): Stream[F, Either[Throwable, Json]] =
    mapping.compiler.compileUntyped(request.query, request.vars).toEither match {
      case Right(operation) =>
        mapping.run(operation.query, operation.rootTpe, Cursor.Env.empty)
          .map(_.asRight[Throwable]) recover { case NonFatal(t) => Left(t) }
      case Left(e) => Stream.emit(Left(e.map(GrackleException(_)).merge))
    }

  def format(err: Throwable): F[GraphQLErrors] =
    Logger[F].error(err)("Error computing GraphQL response.")
      .unlessA(err.isInstanceOf[GrackleException])
      .as {
        NonEmptyList.fromList(
          (err match {
            case GrackleException(ps) => ps
            case ex =>
              // Don't show the details to the user.
              List(Problem(s"An internal error of type ${ex.getClass.getSimpleName} occurred."))
          }).map(_.toGraphQLError)
        ).getOrElse(NonEmptyList.one(GraphQLError("An unspecified error has occurred.")))
      }

}

case class GrackleException(problems: List[Problem]) extends Exception
object GrackleException {
  def apply(problems: NonEmptyChain[Problem]): GrackleException = apply(problems.toList)
  def apply(problem: Problem): GrackleException = apply(List(problem))
}

