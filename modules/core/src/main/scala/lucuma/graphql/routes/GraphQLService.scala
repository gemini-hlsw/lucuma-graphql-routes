// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadThrow
import cats.data.NonEmptyChain
import cats.syntax.all.*
import clue.model.GraphQLExtensions
import fs2.Compiler
import fs2.Stream
import grackle.Mapping
import grackle.Operation
import grackle.Problem
import grackle.Result
import io.circe.Json
import io.circe.JsonObject
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.SpanKind
import org.typelevel.otel4s.trace.Tracer

/**
  * @param props trace properties to be added to root traces (init, subscribe, execute).
  */
class GraphQLService[F[_]: {MonadThrow, Tracer as T}](
  val mapping: Mapping[F],
  val props:   Attribute[?]*
)(using Compiler[F, F]) {

  def isSubscription(op: Operation): Boolean =
    mapping.schema.subscriptionType.exists(_ =:= op.rootTpe)

  def parse(query: String, op: Option[String], vars: Option[JsonObject]): Result[Operation] =
    mapping.compiler.compile(query, op, vars.map(_.toJson), reportUnused = false)

  def query(op: Operation, extensions: Option[GraphQLExtensions] = None): F[Result[Json]] = {
    val _ = extensions
    // I wonder if we should truncate the query
    T.spanBuilder("graphql")
      .withSpanKind(SpanKind.Server)
      .addAttribute(Attribute("graphql.query", op.query.render))
      .build
      .surround:
      runInterpreter(op).compile.toList.map {
        case List(e) => e
        case other   =>
          Result.internalError(
            GrackleException(Problem(s"Expected exactly one result, found ${other.length}."))
          )
      }
  }

  def subscribe(
    op:         Operation,
    extensions: Option[GraphQLExtensions] = None
  ): Stream[F, Result[Json]] = {
    val _ = extensions
    runInterpreter(op)
  }

  // Direct, non-virtual entry into the grackle interpreter. Both `query` and
  // `subscribe` delegate here so that subclasses overriding only `subscribe`
  // (e.g. to add a subscription-specific span) don't accidentally wrap one-off
  // `query` calls as well.
  private def runInterpreter(op: Operation): Stream[F, Result[Json]] =
    mapping.interpreter.run(op.query, op.rootTpe, grackle.Env.EmptyEnv)

}

case class GrackleException(problems: List[Problem]) extends Exception
object GrackleException {
  def apply(problems: NonEmptyChain[Problem]): GrackleException = apply(problems.toList)
  def apply(problem: Problem): GrackleException = apply(List(problem))
}
