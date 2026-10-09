// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import org.http4s.*
import org.http4s.MediaType.`application/graphql-response+json`
import org.http4s.client.websocket.WSFrame
import org.http4s.headers.Accept
import org.http4s.headers.Allow
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.*

// The playground path, the GraphQL path and the WebSocket path are the same. The `Accept` header
// selects the playground or the GraphQL route, and the `Upgrade` header selects the WebSocket route.
class SharedPathSuite extends BaseSuite:

  val graphQLService: GraphQLService[IO] =
    GraphQLService.unvalidated(PingMapping)

  override def routesConfig: RoutesConfig =
    RoutesConfig(graphQLPath = "graphql", wsPath = "graphql", playgroundPath = "graphql")

  // The `Accept` header that a browser sends when it opens a page.
  private val BrowserAccept =
    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

  private def accept(a: String): Accept =
    Accept.parse(a).fold(throw _, identity)

  private def get(accept: Option[String], params: (String, String)*): IO[(Status, Headers, String)] =
    rawResponse: uri =>
      val req = Request[IO](Method.GET, uri.withQueryParams(params.toMap))
      accept.fold(req)(a => req.putHeaders(this.accept(a)))

  extension (headers: Headers)
    private def mediaType: Option[MediaType] = headers.get[`Content-Type`].map(_.mediaType)
    private def vary: Option[String]         = headers.get(ci"Vary").map(_.head.value)

  test("a browser gets the playground"):
    get(BrowserAccept.some).map: (status, headers, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(headers.mediaType, MediaType.text.html.some)
      assertEquals(headers.vary, "Accept".some)
      assert(body.contains("GraphiQL"), body)

  test("a browser gets the playground when the request has a query parameter"):
    get(BrowserAccept.some, "query" -> "{ ping }").map: (status, headers, _) =>
      assertEquals(status, Status.Ok)
      assertEquals(headers.mediaType, MediaType.text.html.some)

  test("a GraphQL client gets a GraphQL response"):
    get(`application/graphql-response+json`.show.some, "query" -> "{ ping }").map: (status, headers, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(headers.mediaType, `application/graphql-response+json`.some)
      assertEquals(headers.vary, "Accept".some)
      assert(body.contains("pong"), body)

  test("a GET request without a query parameter varies by Accept"):
    get(`application/graphql-response+json`.show.some).map: (status, headers, _) =>
      assertEquals(status, Status.UnprocessableContent)
      assertEquals(headers.vary, "Accept".some)

  test("a wildcard gets a GraphQL response"):
    get("*/*".some, "query" -> "{ ping }").map: (status, headers, _) =>
      assertEquals(status, Status.Ok)
      assertEquals(headers.mediaType, `application/graphql-response+json`.some)

  test("a q value of 0 for text/html gets a GraphQL response"):
    get("text/html;q=0, */*".some, "query" -> "{ ping }").map: (status, headers, _) =>
      assertEquals(status, Status.Ok)
      assertEquals(headers.mediaType, `application/graphql-response+json`.some)

  test("the same q value for text/html and for GraphQL gets a GraphQL response"):
    get("text/html, application/json".some, "query" -> "{ ping }").map: (status, headers, _) =>
      assertEquals(status, Status.Ok)
      assertEquals(headers.mediaType, MediaType.application.json.some)

  test("no Accept header gets a GraphQL response"):
    get(none, "query" -> "{ ping }").map: (status, headers, _) =>
      assertEquals(status, Status.Ok)
      assertEquals(headers.mediaType, MediaType.application.json.some)

  test("a POST request gets a GraphQL response"):
    rawResponse: uri =>
      Request[IO](Method.POST, uri)
        .withEntity("""{"query":"{ ping }"}""")
        .withContentType(`Content-Type`(MediaType.application.json))
        .putHeaders(accept(`application/graphql-response+json`.show))
    .map: (status, headers, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(headers.mediaType, `application/graphql-response+json`.some)
      assert(body.contains("pong"), body)

  test("an unsupported method gets status 405"):
    rawResponse(uri => Request[IO](Method.PUT, uri)).map: (status, headers, _) =>
      assertEquals(status, Status.MethodNotAllowed)
      assertEquals(headers.get[Allow].map(_.methods), Set(Method.GET, Method.POST).some)

  test("a WebSocket handshake opens a connection"):
    rawWsFrames(1)(WSFrame.Text("""{"type":"connection_init"}""")).map: frames =>
      assert(frames.exists { case WSFrame.Text(s, _) => s.contains("connection_ack"); case _ => false }, s"got $frames")
