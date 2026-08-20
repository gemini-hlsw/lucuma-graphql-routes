// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import grackle.Result
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json
import io.circe.parser
import org.http4s.*
import org.http4s.headers.Allow
import org.http4s.headers.Authorization
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.*

// Mapping used by RequestErrorSuite. These tests never reach execution, so one query field is
// enough.
object RequestErrorMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query { ping: String! }
  """
  val QueryType    = schema.ref("Query")
  val typeMappings = TypeMappings.unchecked(
    ObjectMapping(QueryType)(
      CursorFieldJson("ping", _ => Result.success(Json.fromString("pong")), Nil)
    )
  )

// Tests the responses to a request that the server cannot process. Each response must carry a
// well-formed GraphQL response body with the GraphQL media type (findings 14 and 20).
class RequestErrorSuite extends BaseSuite:

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(RequestErrorMapping).some.pure[IO]

  private val GraphQLJson = "application/graphql-response+json"

  // The status, the headers and the parsed body of the response to the given request.
  private def response(mkRequest: Uri => Request[IO]): IO[(Status, Headers, Json)] =
    rawResponse(mkRequest).map: (status, headers, text) =>
      (status, headers, parser.parse(text).getOrElse(Json.Null))

  // POST the given text with a `Content-Type` header of `application/json`.
  private def postText(text: String): IO[(Status, Headers, Json)] =
    response: uri =>
      Request[IO](Method.POST, uri)
        .withEntity(text)
        .putHeaders(`Content-Type`(MediaType.application.json))

  private def get(params: (String, String)*): IO[(Status, Headers, Json)] =
    response(uri => Request[IO](Method.GET, params.foldLeft(uri)((u, p) => u.withQueryParam(p._1, p._2))))

  // Assert that the response carries the GraphQL media type and a non-empty `errors` list, and
  // that it carries no `data` entry.
  private def assertErrorBody(headers: Headers, body: Json): Unit =
    val contentType = headers.get(ci"Content-Type").map(_.head.value)
    assert(contentType.exists(_.startsWith(GraphQLJson)), s"Got: $contentType")
    val errors = body.hcursor.downField("errors").as[List[Json]].getOrElse(Nil)
    assert(errors.nonEmpty, s"Expected an errors list, got: ${body.spaces2}")
    assert(!body.hcursor.downField("data").succeeded, s"Expected no data, got: ${body.spaces2}")
    errors.foreach: error =>
      assert(error.hcursor.downField("message").as[String].isRight, s"Expected a message, got: ${error.spaces2}")

  // --- GET ----------------------------------------------------------------------

  test("GET without a query parameter returns 422 with an errors body"):
    get().map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)

  // An empty document parses, but it holds no operation. The specification asks for status 422
  // when the server cannot determine the operation to execute.
  test("GET with an empty query parameter returns 422 with an errors body"):
    get("query" -> "").map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)

  test("GET with a variables parameter that is not JSON returns 422 with an errors body"):
    get("query" -> "query { ping }", "variables" -> "not json").map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)

  test("GET with a variables parameter that is not an object returns 422 with an errors body"):
    get("query" -> "query { ping }", "variables" -> "[1,2,3]").map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)

  // --- POST ---------------------------------------------------------------------

  test("POST with a body that is not JSON returns 400 with an errors body"):
    postText("NONSENSE").map: (status, headers, body) =>
      assertEquals(status, Status.BadRequest)
      assertErrorBody(headers, body)

  test("POST with a truncated JSON body returns 400 with an errors body"):
    postText("""{"query":""").map: (status, headers, body) =>
      assertEquals(status, Status.BadRequest)
      assertErrorBody(headers, body)

  test("POST with an empty body returns 400 with an errors body"):
    postText("").map: (status, headers, body) =>
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

  test("POST with a query entry that is not a string returns 422 with an errors body"):
    postText("""{"query": 42}""").map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)

  // --- unsupported methods --------------------------------------------------------

  test("PUT returns 405 with an errors body"):
    response(uri => Request[IO](Method.PUT, uri).withEntity("""{"query":"query { ping }"}"""))
      .map: (status, headers, body) =>
        assertEquals(status, Status.MethodNotAllowed)
        assertErrorBody(headers, body)

  test("PUT returns an Allow header with the supported methods"):
    response(uri => Request[IO](Method.PUT, uri).withEntity("""{"query":"query { ping }"}"""))
      .map: (_, headers, _) =>
        assertEquals(headers.get[Allow], Allow(Method.GET, Method.POST).some)

  test("DELETE returns 405 with an errors body"):
    response(uri => Request[IO](Method.DELETE, uri)).map: (status, headers, body) =>
      assertEquals(status, Status.MethodNotAllowed)
      assertErrorBody(headers, body)

  // --- a legacy client gets the same status codes ----------------------------------

  test("a legacy client also gets 405 and the GraphQL media type on an error"):
    response: uri =>
      Request[IO](Method.PUT, uri).putHeaders(Header.Raw(ci"Accept", "application/json"))
    .map: (status, headers, body) =>
      assertEquals(status, Status.MethodNotAllowed)
      assertErrorBody(headers, body)
