// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import io.circe.Json
import org.http4s.Headers
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

  // Assert that the response is 422 Unprocessable Content with a well-formed GraphQL JSON body
  // whose first error message names subscriptions as the problem. The 5-second timeout turns a
  // regression (server blocks forever collecting the stream) into a fast failure.
  private def assert422SubscriptionError(response: IO[(Status, Headers, Json)]): IO[Unit] =
    response.timeout(5.seconds).map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)
      val msg = errorsOf(body).head.hcursor.downField("message").as[String].getOrElse("")
      assert(msg.toLowerCase.contains("subscription"), s"Expected message to mention 'subscription', got: $msg")

  test("[http, POST] subscription returns 422 with JSON errors body"):
    assert422SubscriptionError:
      jsonResponse(uri => Request[IO](Method.POST, uri).withEntity(Json.obj("query" -> Json.fromString(query))))

  test("[http, GET] subscription returns 422 with JSON errors body"):
    assert422SubscriptionError(jsonGet("query" -> query))
