// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import grackle.Cursor
import grackle.Query
import grackle.QueryCompiler.IntrospectionLevel
import grackle.Result
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json
import org.http4s.*
import org.http4s.MediaType.`application/graphql-response+json`
import org.http4s.MediaType.application
import org.http4s.circe.*
import org.http4s.headers.Accept

// Mapping used by ResponseStatusSuite. Each field gives one kind of result:
//   ping         - a plain success
//   nullableFail - a field error on a field
//   effectFail   - an effect handler failure, which aborts execution with a bare
//                  `Result.Failure` that carries no data
//   internalFail - an internal error result, which `mkResponse` raises as an exception
//   effectThrow  - an effect handler that raises an exception in `F`
object ResponseStatusMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query {
      ping: String!
      nullableFail: String
      effectFail: String!
      internalFail: String!
      effectThrow: String!
    }
  """
  val QueryType    = schema.ref("Query")

  private val failingHandler = new Query.EffectHandler[IO]:
    def runEffects(queries: List[(Query, Cursor)]): IO[Result[List[Cursor]]] =
      Result.failure[List[Cursor]]("effect boom").pure[IO]

  private val throwingHandler = new Query.EffectHandler[IO]:
    def runEffects(queries: List[(Query, Cursor)]): IO[Result[List[Cursor]]] =
      IO.raiseError(new RuntimeException("secret effect detail"))

  val typeMappings = TypeMappings.unchecked(
    ObjectMapping(QueryType)(
      CursorFieldJson("ping", _ => Result.success(Json.fromString("pong")), Nil),
      CursorFieldJson("nullableFail", _ => Result.failure("nullable boom"), Nil),
      EffectField("effectFail", failingHandler, Nil),
      CursorFieldJson("internalFail", _ => Result.internalError(new RuntimeException("secret internal detail")), Nil),
      EffectField("effectThrow", throwingHandler, Nil)
    )
  )

class ResponseStatusSuite extends BaseSuite:

  val graphQLService: GraphQLService[IO] =
    GraphQLService.unvalidated(ResponseStatusMapping)

  // A request without an `Accept` header counts as a legacy client, which never gets status 294.
  // These tests are about the status of a modern client, so they ask for the GraphQL media type.
  private val AcceptGraphQL = Accept(`application/graphql-response+json`)

  // A legacy client asks for `application/json`, so it never gets status 294. The same
  // `partialSuccessStatus` serves every response with data and errors, so one test is enough.
  private val AcceptJson = Accept(application.json)

  private def post(
    query:         String,
    operationName: Option[String] = None,
    accept:        Accept = AcceptGraphQL,
    variables:     Option[Json] = None
  ): IO[(Status, Headers, Json)] =
    jsonResponse: uri =>
      val fields = List("query" -> Json.fromString(query)) ++
        operationName.map(n => "operationName" -> Json.fromString(n)) ++
        variables.map("variables" -> _)
      Request[IO](Method.POST, uri).withEntity(Json.fromFields(fields)).putHeaders(accept)

  private def hasErrors(body: Json): Boolean =
    errorsOf(body).nonEmpty

  private def hasData(body: Json): Boolean =
    body.hcursor.downField("data").succeeded

  // The `data` entry is present and null. The specification requires this entry for a field error.
  private def hasNullData(body: Json): Boolean =
    body.hcursor.downField("data").as[Option[Json]] == Right(None)

  // --- partial success --------------------------------------------------------

  // Grackle reports a field error as a null `data` entry. The `data` entry is present, so the
  // specification asks for status 294 and forbids a 4xx or 5xx status.
  test("a field error returns 294 with a null data entry"):
    post("query { nullableFail }").map: (status, _, body) =>
      assertEquals(status.code, 294)
      assert(hasNullData(body), body.spaces2)
      assert(hasErrors(body), body.spaces2)

  // A field error next to a field that succeeds still gives status 294.
  test("a field error beside a successful field returns 294"):
    post("query { ping nullableFail }").map: (status, _, body) =>
      assertEquals(status.code, 294)
      assert(hasData(body), body.spaces2)
      assert(hasErrors(body), body.spaces2)

  test("a legacy client gets 200 for a response with data and errors"):
    post("query { nullableFail }", accept = AcceptJson).map: (status, _, body) =>
      assertEquals(status, Status.Ok)
      assert(hasData(body), body.spaces2)
      assert(hasErrors(body), body.spaces2)

  // An effect handler failure aborts execution, so grackle returns a `Result.Failure` with no
  // data. The error comes from execution, so the specification treats it as a field error: the
  // response must have a `data` entry, which is null, and a 2xx status.
  test("an effect handler failure returns 294 with a null data entry"):
    post("query { effectFail }").map: (status, _, body) =>
      assertEquals(status.code, 294)
      assert(hasNullData(body), body.spaces2)
      assert(hasErrors(body), body.spaces2)

  // --- internal errors --------------------------------------------------------

  // The specification requires a well-formed GraphQL response body for the GraphQL media type at
  // every status. An unexpected server error gives status 500 with an `errors` entry, a generic
  // message, and no `data` entry.
  List(
    ("an internal error", "internalFail", "secret internal detail"),
    ("an exception raised by an effect handler", "effectThrow", "secret effect detail")
  ).foreach: (name, field, secret) =>
    test(s"$name returns 500 with a GraphQL error body"):
      post(s"query { $field }").map: (status, headers, body) =>
        assertEquals(status, Status.InternalServerError)
        assertErrorBody(headers, body)
        assert(!body.noSpaces.contains(secret), body.spaces2)

  // --- request errors ---------------------------------------------------------

  test("a document that does not parse returns 400"):
    post("query {").map: (status, _, body) =>
      assertEquals(status, Status.BadRequest)
      assert(hasErrors(body), body.spaces2)
      assert(!hasData(body), body.spaces2)

  test("a document that fails validation returns 422"):
    post("query { nope }").map: (status, _, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assert(hasErrors(body), body.spaces2)

  test("an operation that cannot be determined returns 422"):
    post("query A { ping } query B { ping }", "C".some).map: (status, _, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assert(hasErrors(body), body.spaces2)

  test("a variable value that does not coerce returns 422"):
    post("query Q($i: Int!) { ping }", variables = Json.obj("i" -> Json.fromString("not an int")).some)
      .map((status, _, _) => assertEquals(status, Status.UnprocessableContent))

  // --- a clue client reads the body of a 294 response -------------------------

  // The clue backend reads the body of a response with the GraphQL media type at every status
  // code. Status 294 is the one that a generic HTTP client does not know.
  test("[clue] a field error in a 294 response surfaces as a GraphQL error"):
    interceptGraphQL("nullable boom"):
      this.query(none, "query { nullableFail }", none, BaseSuite.ClientOption.Http)

  // --- a result without data --------------------------------------------------

  // A result that carries no value comes from the parse stage, because the handler gives an
  // execution failure the value `null`. The specification forbids a 2xx status for a response
  // without a `data` entry.
  test("a result without a value returns 422"):
    new HttpRouteHandler(GraphQLService.unvalidated(ResponseStatusMapping), RequestContext.empty, IntrospectionLevel.Full, ResponseMediaType.GraphQL)
      .toResponse(Result.failure[Json]("boom"))
      .map(resp => assertEquals(resp.status, Status.UnprocessableContent))

