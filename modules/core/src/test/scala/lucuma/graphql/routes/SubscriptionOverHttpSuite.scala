// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import io.circe.Json
import io.circe.parser
import org.http4s.Method
import org.http4s.Request
import org.http4s.Status
import org.http4s.circe.*

import scala.concurrent.duration.*

// A subscription over HTTP gets status 422 before the source stream starts. `ticks` never ends,
// so a server that collected the stream would hang.
class SubscriptionOverHttpSuite extends BaseSuite:

  val graphQLService: GraphQLService[IO] =
    GraphQLService.unvalidated(TestMapping)

  private val query = "subscription { ticks }"

  private def request(method: Method): IO[(Status, Json)] =
    rawResponse: uri =>
      method match
        case Method.POST => Request[IO](Method.POST, uri).withEntity(Json.obj("query" -> Json.fromString(query)))
        case _           => Request[IO](method, uri.withQueryParam("query", query))
    .map((status, _, text) => (status, parser.parse(text).getOrElse(Json.Null)))

  // Assert that the response is 422 Unprocessable Content with a well-formed GraphQL JSON body
  // whose first error message names subscriptions as the problem.
  private def assert422SubscriptionError(status: Status, body: Json): Unit =
    assertEquals(status, Status.UnprocessableContent)
    val errors = body.hcursor.downField("errors").as[List[Json]].getOrElse(Nil)
    assert(errors.nonEmpty, s"Expected 'errors' in body, but got: ${body.spaces2}")
    val msg = errors.head.hcursor.downField("message").as[String].getOrElse("")
    assert(msg.toLowerCase.contains("subscription"), s"Expected message to mention 'subscription', got: $msg")

  // The 5-second timeout turns a regression (server blocks forever collecting the stream) into a
  // fast failure.
  test("[http, POST] subscription returns 422 with JSON errors body"):
    request(Method.POST).timeout(5.seconds).map(assert422SubscriptionError)

  test("[http, GET] subscription returns 422 with JSON errors body"):
    request(Method.GET).timeout(5.seconds).map(assert422SubscriptionError)
