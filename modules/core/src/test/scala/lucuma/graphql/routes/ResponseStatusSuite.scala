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
//   ping         - a plain success
//   nullableFail - a field error on a nullable field
//   nonNullFail  - a field error on a non-null field
object ResponseStatusMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query {
      ping: String!
      nullableFail: String
      nonNullFail: String!
    }
  """
  val QueryType    = schema.ref("Query")
  val typeMappings = TypeMappings.unchecked(
    ObjectMapping(QueryType)(
      CursorFieldJson("ping", _ => Result.success(Json.fromString("pong")), Nil),
      CursorFieldJson("nullableFail", _ => Result.failure("nullable boom"), Nil),
      CursorFieldJson("nonNullFail", _ => Result.failure("non-null boom"), Nil)
    )
  )

// Tests the status codes of the GraphQL over HTTP specification (finding 16).
class ResponseStatusSuite extends BaseSuite:

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(ResponseStatusMapping).some.pure[IO]

  // A request without an `Accept` header counts as a legacy client, which never gets status 294.
  // These tests are about the status of a modern client, so they ask for the GraphQL media type.
  private val AcceptGraphQL = Header.Raw(ci"Accept", "application/graphql-response+json")

  private def post(query: String, operationName: Option[String] = None): IO[(Status, Json)] =
    rawResponse: uri =>
      val fields = List("query" -> Json.fromString(query)) ++
        operationName.map(n => "operationName" -> Json.fromString(n))
      Request[IO](Method.POST, uri).withEntity(Json.fromFields(fields)).putHeaders(AcceptGraphQL)
    .map((status, _, body) => (status, parser.parse(body).getOrElse(Json.Null)))

  private def get(query: String): IO[(Status, Json)] =
    rawResponse: uri =>
      Request[IO](Method.GET, uri.withQueryParam("query", query)).putHeaders(AcceptGraphQL)
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

  // Grackle reports a field error as a null `data` entry. The `data` entry is present, so the
  // specification asks for status 294 and forbids a 4xx or 5xx status.
  test("a field error on a nullable field returns 294 with a null data entry"):
    post("query { nullableFail }").map: (status, body) =>
      assertEquals(status.code, 294)
      assertEquals(body.hcursor.downField("data").as[Option[Json]], Right(None))
      assert(hasErrors(body), body.spaces2)

  test("a field error on a non-null field returns 294 with a null data entry"):
    post("query { nonNullFail }").map: (status, body) =>
      assertEquals(status.code, 294)
      assertEquals(body.hcursor.downField("data").as[Option[Json]], Right(None))
      assert(hasErrors(body), body.spaces2)

  // A field error next to a field that succeeds still gives status 294.
  test("a field error beside a successful field returns 294"):
    post("query { ping nullableFail }").map: (status, body) =>
      assertEquals(status.code, 294)
      assert(hasData(body), body.spaces2)
      assert(hasErrors(body), body.spaces2)

  // The specification requires a 2xx status whenever the response carries a `data` entry.
  test("status 294 is a 2xx status"):
    post("query { nullableFail }").map: (status, _) =>
      assert(status.isSuccess, s"Expected a 2xx status, got: $status")

  test("a legacy client gets 200 for a response with data and errors"):
    rawResponse: uri =>
      Request[IO](Method.POST, uri)
        .withEntity(Json.obj("query" -> Json.fromString("query { nullableFail }")))
        .putHeaders(Header.Raw(ci"Accept", "application/json"))
    .map: (status, headers, text) =>
      val body = parser.parse(text).getOrElse(Json.Null)
      assertEquals(status, Status.Ok)
      assert(hasData(body), body.spaces2)
      assert(hasErrors(body), body.spaces2)
      val contentType = headers.get(ci"Content-Type").map(_.head.value)
      assert(contentType.exists(_.startsWith("application/json")), s"Got: $contentType")

  test("a request without an Accept header gets 200 for a response with data and errors"):
    rawResponse: uri =>
      Request[IO](Method.POST, uri)
        .withEntity(Json.obj("query" -> Json.fromString("query { nullableFail }")))
    .map: (status, headers, text) =>
      assertEquals(status, Status.Ok)
      assert(hasErrors(parser.parse(text).getOrElse(Json.Null)))
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

  // --- a clue client reads the body at each of these statuses -----------------

  // The clue backend reads the body of a response with the GraphQL media type at every status
  // code. These tests confirm that the errors reach the caller and not an HTTP status exception.

  test("[clue] a field error in a 294 response surfaces as a GraphQL error"):
    interceptGraphQL("nullable boom"):
      this.query(none, "query { nullableFail }", none, BaseSuite.ClientOption.Http)

  test("[clue] a validation error in a 422 response surfaces as a GraphQL error"):
    interceptGraphQL("No field 'nope' for type Query"):
      this.query(none, "query { nope }", none, BaseSuite.ClientOption.Http)

  test("[clue] a successful query still returns data"):
    this.query(none, "query { ping }", none, BaseSuite.ClientOption.Http)
      .assertEquals(Json.obj("ping" -> Json.fromString("pong")))

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
