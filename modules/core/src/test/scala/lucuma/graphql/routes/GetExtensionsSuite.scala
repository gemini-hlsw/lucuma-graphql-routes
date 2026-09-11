// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import io.circe.Json
import io.circe.literal.*
import io.circe.parser
import org.http4s.*

class GetExtensionsSuite extends BaseSuite:

  val graphQLService: GraphQLService[IO] =
    GraphQLService.unvalidated(RequestErrorMapping)

  private def get(params: (String, String)*): IO[(Status, Json)] =
    rawResponse(uri => Request[IO](Method.GET, params.foldLeft(uri)((u, p) => u.withQueryParam(p._1, p._2))))
      .map((status, _, text) => (status, parser.parse(text).getOrElse(Json.Null)))

  private def errorCount(body: Json): Int =
    body.hcursor.downField("errors").as[List[Json]].getOrElse(Nil).length

  private val query: (String, String) =
    "query" -> "query { ping }"

  test("GET with a valid extensions parameter executes the operation"):
    get(query, "extensions" -> """{"traceparent":"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"}""")
      .map: (status, body) =>
        assertEquals(status, Status.Ok)
        assertEquals(body, json"""{"data":{"ping":"pong"}}""")

  test("GET with an empty extensions object executes the operation"):
    get(query, "extensions" -> "{}").map: (status, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(body, json"""{"data":{"ping":"pong"}}""")

  test("GET with an extensions parameter that is not JSON returns 422"):
    get(query, "extensions" -> "not json").map: (status, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertEquals(errorCount(body), 1)

  test("GET with an extensions parameter that is not a JSON object returns 422"):
    get(query, "extensions" -> "[1,2,3]").map: (status, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertEquals(errorCount(body), 1)

  test("GET reports the errors of both the variables and the extensions parameters"):
    get(query, "variables" -> "not json", "extensions" -> "not json").map: (status, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertEquals(errorCount(body), 2)
