// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadThrow
import cats.data.NonEmptyChain
import cats.syntax.all.*
import fs2.Compiler
import fs2.Stream
import grackle.Mapping
import grackle.Operation
import grackle.Problem
import grackle.Result
import io.circe.Json
import io.circe.JsonObject
import natchez.Trace
import natchez.TraceValue

/**
  * @param props trace properties to be added to root traces (init, subscribe, execute).
  */
class GraphQLService[F[_]: MonadThrow: Trace](
  val mapping: Mapping[F],
  val props: (String, TraceValue)*
)(implicit ev: Compiler[F,F]) {

  def isSubscription(op: Operation): Boolean =
    mapping.schema.subscriptionType.exists(_ =:= op.rootTpe)

  // TODO: a temporary workaround for a bug in 0.18.1 which is fixed in
  // https://github.com/typelevel/grackle/pull/566.
  // Once there is a 0.18.2 or better, we can remove `reportUnused = false`.
  def parse(query: String, op: Option[String], vars: Option[JsonObject]): Result[Operation] =
    mapping.compiler.compile(query, op, vars.map(_.toJson), reportUnused = false)

  def query(op: Operation): F[Result[Json]] =
    Trace[F].span("graphql") {
      Trace[F].put("graphql.query" -> op.query.render) *>
      subscribe(op).compile.toList.map {
        case List(e) => e
        case other   => Result.internalError(GrackleException(Problem(s"Expected exactly one result, found ${other.length}.")))
      }
    }

  def subscribe(op: Operation): Stream[F, Result[Json]] =
    mapping.interpreter.run(op.query, op.rootTpe, grackle.Env.EmptyEnv)

}

case class GrackleException(problems: List[Problem]) extends Exception
object GrackleException {
  def apply(problems: NonEmptyChain[Problem]): GrackleException = apply(problems.toList)
  def apply(problem: Problem): GrackleException = apply(List(problem))
}

