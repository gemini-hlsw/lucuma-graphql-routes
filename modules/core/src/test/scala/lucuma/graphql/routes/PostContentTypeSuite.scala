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
import org.http4s.headers.Authorization
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.*

object PostContentTypeMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query { ping: String! }
  """
  val QueryType    = schema.ref("Query")
  val typeMappings = TypeMappings.unchecked(
    ObjectMapping(QueryType)(
      CursorFieldJson("ping", _ => Result.success(Json.fromString("pong")), Nil)
    )
  )

class PostContentTypeSuite extends BaseSuite:

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(PostContentTypeMapping).some.pure[IO]

  private val body = """{"query":"query { ping }"}"""

  // POST the body with the given `Content-Type` header value. An absent value removes the header,
  // so the request goes out without one.
  private def post(contentType: Option[String]): IO[(Status, Json)] =
    rawResponse: uri =>
      val req = Request[IO](Method.POST, uri).withEntity(body)
      contentType.fold(req.removeHeader[`Content-Type`])(ct => req.putHeaders(Header.Raw(ci"Content-Type", ct)))
    .map((status, _, text) => (status, parser.parse(text).getOrElse(Json.Null)))

  // --- supported content types --------------------------------------------------

  test("POST with application/json succeeds"):
    post("application/json".some).map: (status, _) =>
      assertEquals(status, Status.Ok)

  test("POST with application/json and a charset parameter succeeds"):
    post("application/json; charset=utf-8".some).map: (status, _) =>
      assertEquals(status, Status.Ok)

  // --- unsupported content types ------------------------------------------------

  test("POST without a Content-Type header returns 415"):
    post(none).map: (status, _) =>
      assertEquals(status, Status.UnsupportedMediaType)

  test("POST with text/plain returns 415"):
    post("text/plain".some).map: (status, _) =>
      assertEquals(status, Status.UnsupportedMediaType)

  test("POST with application/graphql returns 415"):
    post("application/graphql".some).map: (status, _) =>
      assertEquals(status, Status.UnsupportedMediaType)

  test("a 415 response carries a GraphQL errors body"):
    post("text/plain".some).map: (_, json) =>
      val errors = json.hcursor.downField("errors").as[List[Json]].getOrElse(Nil)
      assert(errors.nonEmpty, s"Expected an errors list, got: ${json.spaces2}")

  // A body with an unsupported media type must not reach the GraphQL service.
  test("a 415 response does not execute the operation"):
    post("text/plain".some).map: (_, json) =>
      assert(!json.hcursor.downField("data").succeeded, s"Expected no data, got: ${json.spaces2}")

  // --- GET carries no body, so it needs no Content-Type header --------------------

  test("GET without a Content-Type header succeeds"):
    rawResponse(uri => Request[IO](Method.GET, uri.withQueryParam("query", "query { ping }")))
      .map((status, _, _) => assertEquals(status, Status.Ok))
