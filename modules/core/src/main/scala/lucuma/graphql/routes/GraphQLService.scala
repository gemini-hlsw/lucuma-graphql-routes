// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadThrow
import cats.data.NonEmptyChain
import cats.effect.Clock
import cats.effect.Resource.ExitCase
import cats.syntax.all.*
import cats.~>
import fs2.Compiler
import fs2.Stream
import grackle.Mapping
import grackle.Operation
import grackle.Problem
import grackle.QueryCompiler.IntrospectionLevel
import grackle.Result
import grackle.ValidationFailure.Severity
import io.circe.Json
import io.circe.JsonObject
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.AttributeKey
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.semconv.experimental.attributes.GraphqlExperimentalAttributes.*
import org.typelevel.otel4s.trace.Span
import org.typelevel.otel4s.trace.SpanKind
import org.typelevel.otel4s.trace.StatusCode
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*

class GraphQLService[F[_]: {MonadThrow, Clock, Tracer as T}](
  val mapping:        Mapping[F],
  val slowQueryAfter: Option[FiniteDuration] = GraphQLService.DefaultSlowQueryAfter
)(using Compiler[F, F]) {

  def isSubscription(op: Operation): Boolean =
    mapping.schema.subscriptionType.exists(_ =:= op.rootTpe)

  def isMutation(op: Operation): Boolean =
    mapping.schema.mutationType.exists(_ =:= op.rootTpe)

  private def compile(
    ctx:   RequestContext,
    query: String,
    op:    Option[String],
    vars:  Option[JsonObject],
    level: IntrospectionLevel
  ): Result[Operation] =
    mapping.compiler.compile(
      query,
      op,
      vars.map(_.toJson),
      introspectionLevel = level,
      reportUnused       = false,
      env                = ctx.env
    )

  /**
   * Compiles a document under a `graphql.compile` span. `ctx.env` reaches every elaborator, and
   * `level` gates introspection.
   */
  def parse(
    ctx:   RequestContext,
    query: String,
    op:    Option[String],
    vars:  Option[JsonObject],
    level: IntrospectionLevel = IntrospectionLevel.Full
  ): F[Result[Operation]] =
    T.span("graphql.compile").surround(MonadThrow[F].unit.map(_ => compile(ctx, query, op, vars, level)))

  /**
   * True if the GraphQL document parses. The HTTP routes use this to tell a syntax error, which
   * gives status 400, from a validation error, which gives status 422.
   */
  def parses(document: String): Boolean =
    mapping.graphQLParser.parseText(document).hasValue

  private val MaxDocumentLength     = 1024
  private val MaxFullDocumentLength = 64 * 1024

  private def truncate(doc: String, max: Int): String =
    if doc.length <= max then doc
    else s"${doc.take(max)}... (truncated at $max of ${doc.length} chars)"

  private def recordFullDocument(span: Span[F], document: String): F[Unit] =
    span.addAttribute(Attribute(GraphQLService.FullDocumentKey, truncate(document, MaxFullDocumentLength)))

  // OTEL attributes for a GraphQL operation; see
  // https://opentelemetry.io/docs/specs/semconv/graphql/graphql-spans/
  private def graphqlAttributes(
    ctx:           RequestContext,
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
        Attribute(GraphqlDocument, truncate(document, MaxDocumentLength)),
        Attribute(GraphqlOperationType, opType),
      ) ++ operationName.map(Attribute(GraphqlOperationName, _)))*
    ) ++ ctx.attributes
  }

  def query(
    ctx:           RequestContext,
    op:            Operation,
    document:      String,
    operationName: Option[String] = None
  ): F[Result[Json]] =
    T.spanBuilder("graphql")
      .withSpanKind(SpanKind.Server)
      .addAttributes(graphqlAttributes(ctx, op, document, operationName))
      .build
      .use: span =>
        Clock[F]
          .timed(
            runInterpreter(ctx, op).compile.toList
              .map {
                case List(e) => e
                case other   =>
                  Result.internalError(
                    GrackleException(Problem(s"Expected exactly one result, found ${other.length}."))
                  )
              }
          )
          .flatMap {
            case (elapsed, r) =>
              val record = r.isInternalError || slowQueryAfter.exists(elapsed >= _)
              recordFullDocument(span, document).whenA(record).as(r)
          }
          .onError { case _ => recordFullDocument(span, document) }

  def subscribe(
    ctx:           RequestContext,
    op:            Operation,
    document:      String,
    operationName: Option[String] = None
  ): Stream[F, Result[Json]] =
    // The span lives as long as the stream. It is unmanaged because `Stream.resource` needs a
    // `MonadCancel`, which this class does not require.
    Stream
      .eval(
        T.spanBuilder("graphql")
          .withSpanKind(SpanKind.Server)
          .addAttributes(graphqlAttributes(ctx, op, document, operationName))
          .build
          .startUnmanaged
      )
      .flatMap: span =>
        runInterpreter(ctx, op)
          .evalTap {
            case Result.InternalError(_) => recordFullDocument(span, document)
            case _                       => ().pure[F]
          }
          .translate(new (F ~> F) {
            def apply[A](fa: F[A]): F[A] = T.childScope(span.context)(fa)
          })
          .onFinalizeCase {
            case ExitCase.Errored(e) =>
              recordFullDocument(span, document) >> span.recordException(e) >>
                span.setStatus(StatusCode.Error) >> span.end
            case _                   => span.end
          }

  private def runInterpreter(ctx: RequestContext, op: Operation): Stream[F, Result[Json]] =
    mapping.interpreter.run(op.query, op.rootTpe, ctx.env)

}

object GraphQLService {

  /**
   * The full document of an operation that ended in an internal error, or of a query that ran
   * for `slowQueryAfter` or longer, capped at 64K characters.
   */
  val FullDocumentKey: AttributeKey[String] = AttributeKey("graphql.document.full")

  val DefaultSlowQueryAfter: Option[FiniteDuration] = Some(2.seconds)

  /**
   * Builds a service and validates its mapping. It raises `ValidationException` in F when the
   * mapping has a failure of `severity` or greater.
   */
  def apply[F[_]: {MonadThrow, Clock, Tracer}](
    mapping:        Mapping[F],
    severity:       Severity = Severity.Warning,
    slowQueryAfter: Option[FiniteDuration] = DefaultSlowQueryAfter
  )(using Compiler[F, F]): F[GraphQLService[F]] =
    mapping.validateInto[F](severity) *>
      MonadThrow[F]
        .catchNonFatal(mapping.compiler)
        .as(new GraphQLService[F](mapping, slowQueryAfter))

  /**
   * Builds a service and does not validate its mapping. Use it when the caller already validated
   * the mapping on its own terms.
   */
  def unvalidated[F[_]: {MonadThrow, Clock, Tracer}](
    mapping:        Mapping[F],
    slowQueryAfter: Option[FiniteDuration] = DefaultSlowQueryAfter
  )(using Compiler[F, F]): GraphQLService[F] =
    new GraphQLService[F](mapping, slowQueryAfter)

}

case class GrackleException(problems: List[Problem]) extends Exception
object GrackleException {
  def apply(problems: NonEmptyChain[Problem]): GrackleException = apply(problems.toList)
  def apply(problem: Problem): GrackleException = apply(List(problem))
}
