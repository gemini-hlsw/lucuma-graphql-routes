// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadThrow
import cats.data.Ior
import cats.data.NonEmptyChain
import cats.syntax.all._
import edu.gemini.grackle.Cursor
import edu.gemini.grackle.Mapping
import edu.gemini.grackle.Problem
import edu.gemini.grackle.QueryInterpreter
import edu.gemini.grackle.QueryParser
import edu.gemini.grackle.UntypedOperation
import edu.gemini.grackle.UntypedOperation.UntypedSubscription
import fs2.Compiler
import fs2.Stream
import io.circe.Json
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

  def parse(query: String): Either[Throwable, Document] =
    QueryParser.parseText(query).toEither.leftMap(GrackleException(_))

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
        mapping.interpreter.runRoot(operation.query, operation.rootTpe, Cursor.Env.empty).map {
          case Ior.Left(errs) => Left(GrackleException(errs): Throwable)
          case other          => Right(QueryInterpreter.mkResponse(other))
        } recover {
          case NonFatal(t) => Left(t)
        }
      case Left(errs) => Stream.emit(Left(GrackleException(errs)))
    }

  def format(err: Throwable): F[Json] =
    Logger[F].error(err)("Error computing GraphQL response.")
      .unlessA(err.isInstanceOf[GrackleException])
      .as {
        QueryInterpreter.mkResponse(None,
          err match {
            case GrackleException(ps) => ps
            case ex =>
              // Don't show the details to the user.
              List(Problem(s"An internal error of type ${ex.getClass.getSimpleName} occurred."))
          }
        )
      }

}

case class GrackleException(problems: List[Problem]) extends Exception
object GrackleException {
  def apply(problems: NonEmptyChain[Problem]): GrackleException = apply(problems.toList)
  def apply(problem: Problem): GrackleException = apply(List(problem))
}

