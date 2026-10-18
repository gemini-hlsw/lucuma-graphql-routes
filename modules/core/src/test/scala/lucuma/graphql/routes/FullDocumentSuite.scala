// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import cats.effect.Resource
import fs2.Stream
import grackle.Result
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json
import io.opentelemetry.api.common.AttributeKey as JAttributeKey
import io.opentelemetry.sdk.trace.data.SpanData
import munit.CatsEffectSuite
import org.typelevel.otel4s.context.LocalProvider
import org.typelevel.otel4s.oteljava.context.Context
import org.typelevel.otel4s.oteljava.testkit.trace.TracesTestkit
import org.typelevel.otel4s.trace.Tracer

// Fields that end three ways: fine, with an internal error result, and with a thrown exception.
object OutcomeMapping extends CirceMapping[IO]:
  val schema       = schema"""
    type Query { ok: String!, broken: String!, throws: String! }
    type Subscription { broken: String!, throws: String! }
  """
  val QueryType        = schema.ref("Query")
  val SubscriptionType = schema.ref("Subscription")
  val typeMappings = TypeMappings.unchecked(
    ObjectMapping(QueryType, List(
      RootEffect.computeJson("ok")((_, _) => IO.pure(Result(Json.fromString("ok")))),
      RootEffect.computeJson("broken")((_, _) => IO.pure(Result.internalError("boom"))),
      RootEffect.computeJson("throws")((_, _) => IO.raiseError(new RuntimeException("boom")))
    )),
    ObjectMapping(SubscriptionType, List(
      RootStream.computeJson("broken")((_, _) => Stream.emit(Result.internalError("boom"))),
      RootStream.computeJson("throws")((_, _) => Stream.raiseError(new RuntimeException("boom")))
    ))
  )

class FullDocumentSuite extends CatsEffectSuite:

  private val FullDocument = JAttributeKey.stringKey(GraphQLService.FullDocumentKey.name)
  private val Document     = JAttributeKey.stringKey("graphql.document")

  private val testkit: Resource[IO, TracesTestkit[IO]] =
    given LocalProvider[IO, Context] = LocalProvider.fromLiftIO[IO, Context]
    TracesTestkit.inMemory[IO]()

  // Runs `document` with no enclosing span and returns the finished `graphql` span.
  private def run(document: String): IO[SpanData] =
    testkit.use: tk =>
      for
        given Tracer[IO] <- tk.tracerProvider.get("test")
        service           = GraphQLService.unvalidated(OutcomeMapping)
        op                = service.parse(RequestContext.empty, document, None, None).toOption.get
        _                <-
          if service.isSubscription(op) then
            service.subscribe(RequestContext.empty, op, document).compile.drain.attempt
          else service.query(RequestContext.empty, op, document).attempt
        spans            <- tk.finishedSpans
      yield spans.find(_.getName == "graphql").get

  test("A successful operation carries only the truncated document."):
    run("query { ok }").map: span =>
      assertEquals(span.getAttributes.get(Document), "query { ok }")
      assertEquals(Option(span.getAttributes.get(FullDocument)), None)

  test("An internal error result carries the full document."):
    val doc = "query { broken }"
    run(doc).map: span =>
      assertEquals(span.getAttributes.get(FullDocument), doc)

  test("A thrown exception carries the full document."):
    val doc = "query { throws }"
    run(doc).map: span =>
      assertEquals(span.getAttributes.get(FullDocument), doc)

  test("A subscription opens its own span, which carries the full document on an error."):
    val doc = "subscription { broken }"
    run(doc).map: span =>
      assertEquals(span.getAttributes.get(Document), doc)
      assertEquals(span.getAttributes.get(FullDocument), doc)

  test("A subscription that raises carries the full document."):
    val doc = "subscription { throws }"
    run(doc).map: span =>
      assertEquals(span.getAttributes.get(FullDocument), doc)
