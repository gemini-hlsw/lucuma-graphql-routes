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
import org.http4s.circe.*
import org.http4s.headers.Authorization
import org.typelevel.ci.*

// Mapping used by ResponseStatusSuite. Each field gives one kind of result:
//   ping    - a plain success
//   partial - a field error, which gives a response with both data and errors
object ResponseStatusMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query {
      ping: String!
      partial: String
    }
  """
  val QueryType    = schema.ref("Query")
  val typeMappings = TypeMappings.unchecked(
    ObjectMapping(QueryType)(
      CursorFieldJson("ping", _ => Result.success(Json.fromString("pong")), Nil),
      CursorFieldJson("partial", _ => Result.warning("careful", Json.Null), Nil)
    )
  )

// Tests the status codes of the GraphQL over HTTP specification (finding 16).
class ResponseStatusSuite extends BaseSuite:

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(ResponseStatusMapping).some.pure[IO]

  private def post(query: String, operationName: Option[String] = None): IO[(Status, Json)] =
    rawResponse: uri =>
      val fields = List("query" -> Json.fromString(query)) ++
        operationName.map(n => "operationName" -> Json.fromString(n))
      Request[IO](Method.POST, uri).withEntity(Json.fromFields(fields))
    .map((status, _, body) => (status, parser.parse(body).getOrElse(Json.Null)))

  private def get(query: String): IO[(Status, Json)] =
    rawResponse: uri =>
      Request[IO](Method.GET, uri.withQueryParam("query", query))
    .map((status, _, body) => (status, parser.parse(body).getOrElse(Json.Null)))

  private def hasErrors(body: Json): Boolean =
    body.hcursor.downField("errors").as[List[Json]].exists(_.nonEmpty)

  private def hasData(body: Json): Boolean =
    body.hcursor.downField("data").succeeded

  // --- success ----------------------------------------------------------------

  test("a successful query returns 200"):
    post("query { ping }").map: (status, body) =>
      assertEquals(status, Status.Ok)
      assert(hasData(body), body.spaces2)
      assert(!hasErrors(body), body.spaces2)

  // --- partial success --------------------------------------------------------

  test("a response with data and errors returns 294 Partial Success"):
    post("query { partial }").map: (status, body) =>
      assertEquals(status.code, 294)
      assert(hasData(body), body.spaces2)
      assert(hasErrors(body), body.spaces2)

  test("a legacy client gets 200 for a response with data and errors"):
    rawResponse: uri =>
      Request[IO](Method.POST, uri)
        .withEntity(Json.obj("query" -> Json.fromString("query { partial }")))
        .putHeaders(Header.Raw(ci"Accept", "application/json"))
    .map: (status, headers, text) =>
      val body = parser.parse(text).getOrElse(Json.Null)
      assertEquals(status, Status.Ok)
      assert(hasData(body), body.spaces2)
      assert(hasErrors(body), body.spaces2)
      val contentType = headers.get(ci"Content-Type").map(_.head.value)
      assert(contentType.exists(_.startsWith("application/json")), s"Got: $contentType")

  // --- request errors ---------------------------------------------------------

  test("a document that does not parse returns 400"):
    post("query {").map: (status, body) =>
      assertEquals(status, Status.BadRequest)
      assert(hasErrors(body), body.spaces2)
      assert(!hasData(body), body.spaces2)

  test("a document that fails validation returns 422"):
    post("query { nope }").map: (status, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assert(hasErrors(body), body.spaces2)

  test("an operation that cannot be determined returns 422"):
    post("query A { ping } query B { ping }", "C".some).map: (status, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assert(hasErrors(body), body.spaces2)

  test("a variable value that does not coerce returns 422"):
    rawResponse: uri =>
      Request[IO](Method.POST, uri).withEntity(
        Json.obj(
          "query"     -> Json.fromString("query Q($i: Int!) { ping }"),
          "variables" -> Json.obj("i" -> Json.fromString("not an int"))
        )
      )
    .map((status, _, _) => assertEquals(status, Status.UnprocessableContent))

  // --- a result without data --------------------------------------------------

  // Grackle keeps the `data` entry for a field error, so a response without `data` comes from a
  // result that carries no value. The specification forbids a 2xx status for such a response.
  test("a result without a value returns 422"):
    new HttpRouteHandler(GraphQLService(ResponseStatusMapping), ResponseMediaType.GraphQL)
      .toResponse(Result.failure[Json]("boom"))
      .map(resp => assertEquals(resp.status, Status.UnprocessableContent))

  // --- GET uses the same status codes -----------------------------------------

  test("GET of a document that does not parse returns 400"):
    get("query {").map: (status, _) =>
      assertEquals(status, Status.BadRequest)

  test("GET of a document that fails validation returns 422"):
    get("query { nope }").map: (status, _) =>
      assertEquals(status, Status.UnprocessableContent)

  test("GET of a successful query returns 200"):
    get("query { ping }").map: (status, _) =>
      assertEquals(status, Status.Ok)
