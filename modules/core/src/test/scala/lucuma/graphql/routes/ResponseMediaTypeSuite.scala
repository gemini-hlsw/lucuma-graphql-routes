// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import grackle.Result
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json
import org.http4s.*
import org.http4s.MediaType.`application/graphql-response+json`
import org.http4s.MediaType.application
import org.http4s.MediaType.text
import org.http4s.circe.*
import org.http4s.headers.Accept
import org.http4s.headers.Authorization
import org.http4s.headers.`Content-Type`

// Mapping used by ResponseMediaTypeSuite. A single query field is enough, because these tests
// look at the `Content-Type` header of the response and not at the body.
object ResponseMediaTypeMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query { ping: String! }
  """
  val QueryType    = schema.ref("Query")
  val typeMappings = TypeMappings.unchecked(
    ObjectMapping(QueryType)(
      CursorFieldJson("ping", _ => Result.success(Json.fromString("pong")), Nil)
    )
  )

class ResponseMediaTypeSuite extends BaseSuite:

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(ResponseMediaTypeMapping).some.pure[IO]

  private val GraphQLJson = "application/graphql-response+json"
  private val LegacyJson  = "application/json"

  // POST a query with the given `Accept` header value. An absent value sends no header.
  private def post(accept: Option[String], query: String = "query { ping }"): IO[(Status, Option[`Content-Type`])] =
    rawResponse: uri =>
      val req = Request[IO](Method.POST, uri).withEntity(Json.obj("query" -> Json.fromString(query)))
      accept.flatMap(Accept.parse(_).toOption).fold(req)(req.putHeaders(_))
    .map((status, headers, _) => (status, headers.get[`Content-Type`]))

  private def get(accept: Option[String]): IO[(Status, Option[`Content-Type`])] =
    rawResponse: uri =>
      val req = Request[IO](Method.GET, uri.withQueryParam("query", "query { ping }"))
      accept.flatMap(Accept.parse(_).toOption).fold(req)(req.putHeaders(_))
    .map((status, headers, _) => (status, headers.get[`Content-Type`]))

  // --- the server honors the Accept header ------------------------------------

  test("Accept of the GraphQL media type gives the GraphQL media type"):
    post(GraphQLJson.some).map: (status, contentType) =>
      assertEquals(status, Status.Ok)
      assertEquals(contentType.map(_.mediaType), `application/graphql-response+json`.some)

  test("Accept of the legacy media type gives the legacy media type on a 200 response"):
    post(LegacyJson.some).map: (status, contentType) =>
      assertEquals(status, Status.Ok)
      assertEquals(contentType.map(_.mediaType), application.json.some)
      assertNotEquals(contentType.map(_.mediaType), `application/graphql-response+json`.some)

  test("the server obeys the q value of the Accept header"):
    post(s"$GraphQLJson;q=0.5, $LegacyJson;q=0.9".some).map: (_, contentType) =>
      assertEquals(contentType.map(_.mediaType), application.json.some)

  test("the server prefers the GraphQL media type at an equal q value"):
    post(s"$LegacyJson, $GraphQLJson".some).map: (_, contentType) =>
      assertEquals(contentType.map(_.mediaType), `application/graphql-response+json`.some)

  test("a wildcard Accept gives the GraphQL media type"):
    post("*/*".some).map: (_, contentType) =>
      assertEquals(contentType.map(_.mediaType), `application/graphql-response+json`.some)

  test("an absent Accept header gives the legacy media type"):
    post(none).map: (status, contentType) =>
      assertEquals(status, Status.Ok)
      assertEquals(contentType.map(_.mediaType), application.json.some)

  test("the response declares the utf-8 charset"):
    post(GraphQLJson.some).map: (_, contentType) =>
      assertEquals(contentType.flatMap(_.charset), Charset.`UTF-8`.some)

  // --- no acceptable media type -----------------------------------------------

  test("an Accept header without a supported media type gives 406"):
    post("text/html".some).map: (status, _) =>
      assertEquals(status, Status.NotAcceptable)

  test("a 406 response does not use the GraphQL media type"):
    post("text/html".some).map: (_, contentType) =>
      assert(!contentType.exists(_.mediaType.show.contains("graphql-response+json")))

  test("a 406 response carries the message of the negotiation"):
    rawResponse: uri =>
      Request[IO](Method.POST, uri)
        .withEntity(Json.obj("query" -> Json.fromString("query { ping }")))
        .putHeaders(Accept(text.html))
    .map: (_, _, body) =>
      assertEquals(body, s"Unsupported 'Accept' header 'text/html'. Supported media types are '$GraphQLJson' and '$LegacyJson'.")

  // A q value of 0 means that the client refuses the media type.
  test("a q value of 0 for every supported media type gives 406"):
    post(s"$GraphQLJson;q=0, $LegacyJson;q=0".some).map: (status, _) =>
      assertEquals(status, Status.NotAcceptable)

  // --- the legacy media type applies only to a 2xx response --------------------

  test("a legacy client gets the GraphQL media type on an error response"):
    post(LegacyJson.some, "query { nope }").map: (status, contentType) =>
      assert(!status.isSuccess, s"Expected an error status, got: $status")
      assertEquals(contentType.map(_.mediaType), `application/graphql-response+json`.some)

  // --- GET negotiates in the same way -----------------------------------------

  test("GET obeys the Accept header"):
    get(LegacyJson.some).map: (status, contentType) =>
      assertEquals(status, Status.Ok)
      assertEquals(contentType.map(_.mediaType), application.json.some)

  test("GET with an Accept header without a supported media type gives 406"):
    get("text/html".some).map: (status, _) =>
      assertEquals(status, Status.NotAcceptable)
