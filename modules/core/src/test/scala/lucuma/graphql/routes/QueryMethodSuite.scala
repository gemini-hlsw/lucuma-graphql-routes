// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.data.NonEmptyList
import cats.effect.*
import cats.implicits.*
import io.circe.Json
import io.circe.literal.*
import org.http4s.*
import org.http4s.headers.Allow
import org.http4s.headers.`Accept-Query`
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.*

// A QUERY request has the same body as a POST request, but it is safe, so it must not run a mutation.
class QueryMethodSuite extends BaseSuite:

  val graphQLService: GraphQLService[IO] =
    GraphQLService.unvalidated(GetMutationMapping)

  private val Vary = ci"Vary"

  // The media types of the `Accept-Query` header of a response.
  private def acceptQuery(headers: Headers): Option[NonEmptyList[MediaType]] =
    headers.get[`Accept-Query`].map(_.values)

  private val jsonOnly: Option[NonEmptyList[MediaType]] =
    NonEmptyList.one(MediaType.application.json).some

  // Send a QUERY request with the given body. An absent `Content-Type` value removes the
  // header, so the request goes out without one.
  private def queryRequest(body: String, contentType: Option[String] = "application/json".some): IO[(Status, Headers, Json)] =
    jsonResponse: uri =>
      val req = Request[IO](Method.QUERY, uri).withEntity(body)
      contentType.fold(req.removeHeader[`Content-Type`])(ct => req.putHeaders(`Content-Type`.parse(ct)))

  private val mutationDoc = "mutation { increment }"
  private val mixedDoc    = "query Ping { ping } mutation Inc { increment }"

  test("QUERY a query returns 200 and the result"):
    queryRequest("""{"query":"query { ping }"}""").map: (status, _, body) =>
      assertEquals(status, Status.Ok)
      assertEquals(body, json"""{"data":{"ping":"pong"}}""")

  // A cache can store the response to a QUERY request, and the response depends on the
  // `Accept` header of the request.
  test("QUERY varies by Accept"):
    queryRequest("""{"query":"query { ping }"}""").map: (_, headers, _) =>
      assertEquals(headers.get(Vary).map(_.head.value), "Accept".some)

  test("QUERY a query operation from a mixed document succeeds"):
    queryRequest(s"""{"query":"$mixedDoc","operationName":"Ping"}""").map: (status, _, _) =>
      assertEquals(status, Status.Ok)

  // --- mutations ------------------------------------------------------------------

  // The pull request requires status 422 for a mutation on a QUERY request, not status 405.
  test("QUERY a mutation returns 422 with an errors body"):
    queryRequest(s"""{"query":"$mutationDoc"}""").map: (status, headers, body) =>
      assertEquals(status, Status.UnprocessableContent)
      assertErrorBody(headers, body)

  test("QUERY a mutation does not execute the mutation side effect"):
    val before = GetMutationMapping.counter.get()
    queryRequest(s"""{"query":"$mutationDoc"}""").map: _ =>
      assertEquals(GetMutationMapping.counter.get(), before)

  test("QUERY a mutation operation from a mixed document returns 422"):
    queryRequest(s"""{"query":"$mixedDoc","operationName":"Inc"}""").map: (status, _, _) =>
      assertEquals(status, Status.UnprocessableContent)

  // --- the Content-Type header ----------------------------------------------------

  // RFC 10008 requires a failure when the `Content-Type` header is absent. The `Accept-Query`
  // header tells the client which media type to use.
  test("QUERY without a Content-Type header returns 415 with an Accept-Query header"):
    queryRequest("""{"query":"query { ping }"}""", none).map: (status, headers, body) =>
      assertEquals(status, Status.UnsupportedMediaType)
      assertErrorBody(headers, body)
      assertEquals(acceptQuery(headers), jsonOnly)

  test("QUERY with an unsupported Content-Type returns 415 with an Accept-Query header"):
    queryRequest("""{"query":"query { ping }"}""", "text/plain".some).map: (status, headers, _) =>
      assertEquals(status, Status.UnsupportedMediaType)
      assertEquals(acceptQuery(headers), jsonOnly)

  test("QUERY with a body that is not JSON returns 400"):
    queryRequest("NONSENSE").map: (status, _, _) =>
      assertEquals(status, Status.BadRequest)

  // --- discovery ------------------------------------------------------------------

  // RFC 10008 uses OPTIONS and the `Allow` header for the discovery of QUERY support.
  test("OPTIONS lists QUERY in the Allow header and sends an Accept-Query header"):
    rawResponse(uri => Request[IO](Method.OPTIONS, uri)).map: (status, headers, _) =>
      assertEquals(status, Status.NoContent)
      assertEquals(headers.get[Allow].map(_.methods), Set(Method.GET, Method.POST, Method.QUERY, Method.OPTIONS).some)
      assertEquals(acceptQuery(headers), jsonOnly)
