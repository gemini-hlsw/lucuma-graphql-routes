// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import io.circe.Json
import io.circe.literal.*
import org.http4s.*
import org.http4s.headers.Allow
import org.http4s.headers.`Content-Type`

// The decoding of a GraphQL-over-HTTP request. A request that is not well-formed never reaches
// execution. Each test covers one branch of the decoding: the method, the `Content-Type` header,
// the body, and the parameters.
class RequestErrorSuite extends BaseSuite:

  val graphQLService: GraphQLService[IO] =
    GraphQLService.unvalidated(PingMapping)

  // POST the given text with a `Content-Type` header of `application/json`.
  private def postText(text: String): IO[(Status, Headers, Json)] =
    jsonResponse: uri =>
      Request[IO](Method.POST, uri)
        .withEntity(text)
        .putHeaders(`Content-Type`(MediaType.application.json))

  // POST a valid body with the given `Content-Type` header value. An absent value removes the
  // header, so the request goes out without one.
  private def postWithContentType(contentType: Option[String]): IO[(Status, Headers, Json)] =
    jsonResponse: uri =>
      val req = Request[IO](Method.POST, uri).withEntity("""{"query":"query { ping }"}""")
      contentType.fold(req.removeHeader[`Content-Type`])(ct => req.putHeaders(`Content-Type`.parse(ct)))

  private val query: (String, String) =
    "query" -> "query { ping }"

  private val pong: Json =
    json"""{"data":{"ping":"pong"}}"""

  // --- unsupported methods --------------------------------------------------------

  // RFC 9110 requires the `Allow` header with status 405.
  test("PUT returns 405 with an errors body and an Allow header"):
    jsonResponse(uri => Request[IO](Method.PUT, uri).withEntity("""{"query":"query { ping }"}"""))
      .map: (status, headers, body) =>
        assertEquals(status, Status.MethodNotAllowed)
        assertErrorBody(headers, body)
        assertEquals(headers.get[Allow], Allow(Method.GET, Method.POST).some)

  // --- the Content-Type header of a POST ------------------------------------------

  test("POST with application/json succeeds"):
    postWithContentType("application/json".some).map: (status, _, _) =>
      assertEquals(status, Status.Ok)

  test("POST with application/json and a charset parameter succeeds"):
    postWithContentType("application/json; charset=utf-8".some).map: (status, _, _) =>
      assertEquals(status, Status.Ok)

  test("POST without a Content-Type header returns 415"):
    postWithContentType(none).map: (status, _, _) =>
      assertEquals(status, Status.UnsupportedMediaType)

  // A body with an unsupported media type must not reach the GraphQL service.
  test("POST with an unsupported Content-Type returns 415 with an errors body"):
    postWithContentType("text/plain".some).map: (status, headers, body) =>
      assertEquals(status, Status.UnsupportedMediaType)
      assertErrorBody(headers, body)

  // --- the body of a POST ---------------------------------------------------------

  test("POST with a body that is not JSON returns 400 with an errors body"):
    postText("NONSENSE").map: (status, headers, body) =>
      assertEquals(status, Status.BadRequest)
      assertErrorBody(headers, body)

  test("POST with a body that is not a JSON object returns 422 with an errors body"):
    postText("""["query"]""").map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)

  test("POST without a query entry returns 422 with an errors body"):
    postText("""{"qeury": "{__typename}"}""").map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)

  // --- the optional parameters of a POST body -------------------------------------

  // One error per parameter proves that the server checks each of them, and that it reports them
  // together.
  test("POST reports the errors of every parameter of the wrong type"):
    postText("""{"query":"query { ping }","operationName":42,"variables":[],"extensions":7}""")
      .map: (status, headers, body) =>
        assertEquals(status, Status.UnprocessableContent)
        assertErrorBody(headers, body)
        assertEquals(errorsOf(body).size, 3)

  test("POST with a null parameter counts the parameter as absent"):
    postText("""{"query":"query { ping }","operationName":null,"variables":null,"extensions":null}""")
      .map: (status, _, body) =>
        assertEquals(status, Status.Ok)
        assertEquals(body, pong)

  test("POST with an operationName of type string executes the named operation"):
    postText("""{"query":"query Ping { ping }","operationName":"Ping"}""").map: (status, _, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(body, pong)

  // --- the parameters of a GET ----------------------------------------------------

  test("GET without a query parameter returns 422 with an errors body"):
    jsonGet().map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)

  // An empty document parses, but it holds no operation. The specification asks for status 422
  // when the server cannot determine the operation to execute.
  test("GET with an empty query parameter returns 422 with an errors body"):
    jsonGet("query" -> "").map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)

  // The `variables` and the `extensions` parameters share one decoder.
  test("GET with a variables parameter that is not JSON returns 422 with an errors body"):
    jsonGet(query, "variables" -> "not json").map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)

  test("GET with a variables parameter that is not an object returns 422 with an errors body"):
    jsonGet(query, "variables" -> "[1,2,3]").map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)

  test("GET reports the errors of both the variables and the extensions parameters"):
    jsonGet(query, "variables" -> "not json", "extensions" -> "not json").map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)
      assertEquals(errorsOf(body).size, 2)

  test("GET with a valid extensions parameter executes the operation"):
    jsonGet(query, "extensions" -> """{"traceparent":"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"}""")
      .map: (status, _, body) =>
        assertEquals(status, Status.Ok)
        assertEquals(body, pong)
