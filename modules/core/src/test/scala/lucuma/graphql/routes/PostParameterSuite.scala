// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import io.circe.Json
import io.circe.literal.*
import io.circe.parser
import org.http4s.*
import org.http4s.headers.Authorization
import org.http4s.headers.`Content-Type`

class PostParameterSuite extends BaseSuite:

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(RequestErrorMapping).some.pure[IO]

  private def post(text: String): IO[(Status, Json)] =
    rawResponse: uri =>
      Request[IO](Method.POST, uri)
        .withEntity(text)
        .putHeaders(`Content-Type`(MediaType.application.json))
    .map((status, _, body) => (status, parser.parse(body).getOrElse(Json.Null)))

  private def errorCount(body: Json): Int =
    body.hcursor.downField("errors").as[List[Json]].getOrElse(Nil).length

  private val pong: Json =
    json"""{"data":{"ping":"pong"}}"""

  // --- a parameter of the wrong type ----------------------------------------------

  test("POST with an operationName that is not a string returns 422"):
    post("""{"query":"query { ping }","operationName":42}""").map: (status, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertEquals(errorCount(body), 1)

  test("POST with variables that are not a JSON object returns 422"):
    post("""{"query":"query { ping }","variables":[1,2,3]}""").map: (status, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertEquals(errorCount(body), 1)

  test("POST with extensions that are not a JSON object returns 422"):
    post("""{"query":"query { ping }","extensions":"nope"}""").map: (status, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertEquals(errorCount(body), 1)

  test("POST reports the errors of every parameter of the wrong type"):
    post("""{"query":"query { ping }","operationName":42,"variables":[],"extensions":7}""")
      .map: (status, body) =>
        assertEquals(status, Status.UnprocessableContent)
        assertEquals(errorCount(body), 3)

  // --- a parameter with the value null counts as absent ----------------------------

  test("POST with a null operationName executes the operation"):
    post("""{"query":"query { ping }","operationName":null}""").map: (status, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(body, pong)

  test("POST with null variables and null extensions executes the operation"):
    post("""{"query":"query { ping }","variables":null,"extensions":null}""").map: (status, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(body, pong)

  // --- a parameter of the right type still works -----------------------------------

  test("POST with an operationName of type string executes the named operation"):
    post("""{"query":"query Ping { ping }","operationName":"Ping"}""").map: (status, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(body, pong)
