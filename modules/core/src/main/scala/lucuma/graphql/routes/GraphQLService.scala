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
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.semconv.experimental.attributes.GraphqlExperimentalAttributes.*
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

  def isMutation(op: Operation): Boolean =
    mapping.schema.mutationType.exists(_ =:= op.rootTpe)

  def parse(query: String, op: Option[String], vars: Option[JsonObject]): Result[Operation] =
    mapping.compiler.compile(query, op, vars.map(_.toJson), reportUnused = false)

  private val MaxDocumentLength = 1024

  private def truncateDocument(doc: String): String =
    if doc.length <= MaxDocumentLength then doc
    else s"${doc.take(MaxDocumentLength)}... (truncated at $MaxDocumentLength} of ${doc.length} chars)"

  // OTEL attributes for a GraphQL operation; see
  // https://opentelemetry.io/docs/specs/semconv/graphql/graphql-spans/
  private def graphqlAttributes(
    op:            Operation,
    document:      String,
    operationName: Option[String]
  ): Attributes = {
    val opType =
      if isSubscription(op)  then GraphqlOperationTypeValue.Subscription.value
      else if isMutation(op) then GraphqlOperationTypeValue.Mutation.value
      else                   GraphqlOperationTypeValue.Query.value

    Attributes(
      (List(
        Attribute(GraphqlDocument, truncateDocument(document)),
        Attribute(GraphqlOperationType, opType),
      ) ++ operationName.map(Attribute(GraphqlOperationName, _)) ++ props)*
    )
  }

  def query(
    op:            Operation,
    document:      String,
    operationName: Option[String] = None
  ): F[Result[Json]] =
    T.spanBuilder("graphql")
      .withSpanKind(SpanKind.Server) // it is assumed we run this on the serevr
      .addAttributes(graphqlAttributes(op, document, operationName))
      .build
      .surround:
        runInterpreter(op).compile.toList.map {
          case List(e) => e
          case other   =>
            Result.internalError(
              GrackleException(Problem(s"Expected exactly one result, found ${other.length}."))
            )
        }

  def subscribe(
    op:            Operation,
    document:      String,
    operationName: Option[String] = None
  ): Stream[F, Result[Json]] =
    // Decorate the current span with GraphQL semantic attributes.
    Stream.exec(
      T.withCurrentSpanOrNoop(_.addAttributes(graphqlAttributes(op, document, operationName)))
    ) ++ runInterpreter(op)

  private def runInterpreter(op: Operation): Stream[F, Result[Json]] =
    mapping.interpreter.run(op.query, op.rootTpe, grackle.Env.EmptyEnv)

}

case class GrackleException(problems: List[Problem]) extends Exception
object GrackleException {
  def apply(problems: NonEmptyChain[Problem]): GrackleException = apply(problems.toList)
  def apply(problem: Problem): GrackleException = apply(List(problem))
}
