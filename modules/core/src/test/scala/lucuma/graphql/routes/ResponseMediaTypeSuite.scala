// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import grackle.Result
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json
import org.http4s.*
import org.http4s.circe.*
import org.http4s.headers.Authorization
import org.typelevel.ci.*

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

// Tests the content negotiation of the GraphQL over HTTP specification (finding 15).
class ResponseMediaTypeSuite extends BaseSuite:

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(ResponseMediaTypeMapping).some.pure[IO]

  private val GraphQLJson = "application/graphql-response+json"
  private val LegacyJson  = "application/json"

  // POST a query with the given `Accept` header value. An absent value sends no header.
  private def post(accept: Option[String], query: String = "query { ping }"): IO[(Status, Option[String])] =
    rawResponse: uri =>
      val req = Request[IO](Method.POST, uri).withEntity(Json.obj("query" -> Json.fromString(query)))
      accept.fold(req)(a => req.putHeaders(Header.Raw(ci"Accept", a)))
    .map((status, headers, _) => (status, headers.get(ci"Content-Type").map(_.head.value)))

  private def get(accept: Option[String]): IO[(Status, Option[String])] =
    rawResponse: uri =>
      val req = Request[IO](Method.GET, uri.withQueryParam("query", "query { ping }"))
      accept.fold(req)(a => req.putHeaders(Header.Raw(ci"Accept", a)))
    .map((status, headers, _) => (status, headers.get(ci"Content-Type").map(_.head.value)))

  // --- the server honors the Accept header ------------------------------------

  test("Accept of the GraphQL media type gives the GraphQL media type"):
    post(GraphQLJson.some).map: (status, contentType) =>
      assertEquals(status, Status.Ok)
      assert(contentType.exists(_.startsWith(GraphQLJson)), s"Got: $contentType")

  test("Accept of the legacy media type gives the legacy media type on a 200 response"):
    post(LegacyJson.some).map: (status, contentType) =>
      assertEquals(status, Status.Ok)
      assert(contentType.exists(_.startsWith(LegacyJson)), s"Got: $contentType")
      assert(!contentType.exists(_.startsWith(GraphQLJson)), s"Got: $contentType")

  test("the server obeys the q value of the Accept header"):
    post(s"$GraphQLJson;q=0.5, $LegacyJson;q=0.9".some).map: (_, contentType) =>
      assert(contentType.exists(_.startsWith(LegacyJson)), s"Got: $contentType")

  test("the server prefers the GraphQL media type at an equal q value"):
    post(s"$LegacyJson, $GraphQLJson".some).map: (_, contentType) =>
      assert(contentType.exists(_.startsWith(GraphQLJson)), s"Got: $contentType")

  test("a wildcard Accept gives the GraphQL media type"):
    post("*/*".some).map: (_, contentType) =>
      assert(contentType.exists(_.startsWith(GraphQLJson)), s"Got: $contentType")

  test("an absent Accept header gives the GraphQL media type"):
    post(none).map: (_, contentType) =>
      assert(contentType.exists(_.startsWith(GraphQLJson)), s"Got: $contentType")

  test("the response declares the utf-8 charset"):
    post(GraphQLJson.some).map: (_, contentType) =>
      assert(contentType.exists(_.toLowerCase.contains("charset=utf-8")), s"Got: $contentType")

  // --- no acceptable media type -----------------------------------------------

  test("an Accept header without a supported media type gives 406"):
    post("text/html".some).map: (status, _) =>
      assertEquals(status, Status.NotAcceptable)

  test("a 406 response does not use the GraphQL media type"):
    post("text/html".some).map: (_, contentType) =>
      assert(!contentType.exists(_.contains("graphql-response+json")), s"Got: $contentType")

  // A q value of 0 means that the client refuses the media type.
  test("a q value of 0 for every supported media type gives 406"):
    post(s"$GraphQLJson;q=0, $LegacyJson;q=0".some).map: (status, _) =>
      assertEquals(status, Status.NotAcceptable)

  // --- the legacy media type applies only to a 2xx response --------------------

  test("a legacy client gets the GraphQL media type on an error response"):
    post(LegacyJson.some, "query { nope }").map: (status, contentType) =>
      assert(!status.isSuccess, s"Expected an error status, got: $status")
      assert(contentType.exists(_.startsWith(GraphQLJson)), s"Got: $contentType")

  // --- GET negotiates in the same way -----------------------------------------

  test("GET obeys the Accept header"):
    get(LegacyJson.some).map: (status, contentType) =>
      assertEquals(status, Status.Ok)
      assert(contentType.exists(_.startsWith(LegacyJson)), s"Got: $contentType")

  test("GET with an Accept header without a supported media type gives 406"):
    get("text/html".some).map: (status, _) =>
      assertEquals(status, Status.NotAcceptable)
