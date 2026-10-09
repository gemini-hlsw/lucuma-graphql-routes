// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import io.circe.Json
import org.http4s.*
import org.http4s.MediaType.`application/graphql-response+json`
import org.http4s.MediaType.application
import org.http4s.circe.*
import org.http4s.headers.Accept
import org.http4s.headers.`Content-Type`

// The routes select the media type of the response from the `Accept` header. `NegotiateSuite`
// covers the selection itself. These tests cover the wiring: the selected media type reaches the
// `Content-Type` header, and a failed selection gives status 406.
class ResponseMediaTypeSuite extends BaseSuite:

  val graphQLService: GraphQLService[IO] =
    GraphQLService.unvalidated(PingMapping)

  private val GraphQLJson = "application/graphql-response+json"
  private val LegacyJson  = "application/json"

  // POST a query with the given `Accept` header value.
  private def post(accept: String, query: String = "query { ping }"): IO[(Status, Option[MediaType], String)] =
    rawResponse: uri =>
      Request[IO](Method.POST, uri)
        .withEntity(Json.obj("query" -> Json.fromString(query)))
        .putHeaders(Accept.parse(accept).fold(throw _, identity))
    .map((status, headers, body) => (status, headers.get[`Content-Type`].map(_.mediaType), body))

  test("Accept of the GraphQL media type gives the GraphQL media type"):
    post(GraphQLJson).map: (status, mediaType, _) =>
      assertEquals(status, Status.Ok)
      assertEquals(mediaType, `application/graphql-response+json`.some)

  test("Accept of the legacy media type gives the legacy media type on a 200 response"):
    post(LegacyJson).map: (status, mediaType, _) =>
      assertEquals(status, Status.Ok)
      assertEquals(mediaType, application.json.some)

  test("a legacy client gets the GraphQL media type on an error response"):
    post(LegacyJson, "query { nope }").map: (status, mediaType, _) =>
      assert(!status.isSuccess, s"Expected an error status, got: $status")
      assertEquals(mediaType, `application/graphql-response+json`.some)

  test("an Accept header without a supported media type gives 406 with the message of the negotiation"):
    post("text/html").map: (status, mediaType, body) =>
      assertEquals(status, Status.NotAcceptable)
      assert(!mediaType.contains(`application/graphql-response+json`), s"got $mediaType")
      assertEquals(body, s"Unsupported 'Accept' header 'text/html'. Supported media types are '$GraphQLJson' and '$LegacyJson'.")
