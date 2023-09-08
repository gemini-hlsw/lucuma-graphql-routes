// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadThrow
import cats.data.NonEmptyChain
import cats.data.NonEmptyList
import cats.syntax.all._
import clue.model.GraphQLError
import clue.model.GraphQLErrors
import edu.gemini.grackle.Mapping
import edu.gemini.grackle.Problem
import fs2.Compiler
import fs2.Stream
import io.circe.Json
import lucuma.graphql.routes.conversions._
import natchez.Trace
import org.typelevel.log4cats.Logger

import edu.gemini.grackle.Operation
import edu.gemini.grackle.Result.Failure
import edu.gemini.grackle.Result
import edu.gemini.grackle.Result.Success
import edu.gemini.grackle.Result.Warning
import io.circe.JsonObject
import edu.gemini.grackle.Env

class GrackleGraphQLService[F[_]: MonadThrow: Logger: Trace](
  mapping: Mapping[F],
)(implicit ev: Compiler[F,F]) extends GraphQLService[F] {

  def isSubscription(req: Operation): Boolean =
    mapping.schema.subscriptionType.exists(_ =:= req.rootTpe)

  def parse(query: String, op: Option[String], vars: Option[JsonObject]): Either[Throwable, Operation] =
    mapping.compiler.compile(query, op, vars.map(_.toJson)) match {
      case Result.InternalError(error) => Left(error)
      case Success(value) => Right(value)
      case Failure(problems) => Left(GrackleException(problems))
      case Warning(_, value) => Right(value) // todo: log warnings
    }

  def query(request: Operation): F[Either[Throwable, Json]] =
    Trace[F].span("graphql") {
      Trace[F].put("graphql.query" -> request.query.render) *>
      subscribe(request).compile.toList.map {
        case List(e) => e
        case other   => GrackleException(Problem(s"Expected exactly one result, found ${other.length}.")).asLeft
      }
    }

  def subscribe(op: Operation): Stream[F, Either[Throwable, Json]] =
    // HMM: we don't get throwables on the left anymore
    mapping.interpreter.run(op.query, op.rootTpe, Env.EmptyEnv).evalMap(mapping.mkResponse).map(_.asRight)

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
