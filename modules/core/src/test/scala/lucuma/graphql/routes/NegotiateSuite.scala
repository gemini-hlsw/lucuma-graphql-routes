// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.implicits.*
import munit.FunSuite
import org.http4s.Charset
import org.http4s.Header
import org.http4s.Headers
import org.http4s.Status
import org.typelevel.ci.*

// Tests `ResponseMediaType.negotiate` and the media type of a response without an HTTP server.
class NegotiateSuite extends FunSuite:

  private val GraphQLJson = "application/graphql-response+json"
  private val LegacyJson  = "application/json"

  private def negotiate(accept: String*): Either[String, ResponseMediaType] =
    ResponseMediaType.negotiateOrError(Headers(accept.map(a => Header.Raw(ci"Accept", a))*))

  private def message(accept: String*): String =
    negotiate(accept*).swap.getOrElse(fail(s"Expected no acceptable media type for ${accept.toList}"))

  // --- the server selects a media type ----------------------------------------

  test("an absent Accept header gives the legacy media type"):
    assertEquals(negotiate(), ResponseMediaType.LegacyJson.asRight)

  test("an empty Accept header gives the legacy media type"):
    assertEquals(negotiate(""), ResponseMediaType.LegacyJson.asRight)

  test("a wildcard gives the GraphQL media type"):
    assertEquals(negotiate("*/*"), ResponseMediaType.GraphQL.asRight)

  test("a subtype wildcard gives the GraphQL media type"):
    assertEquals(negotiate("application/*"), ResponseMediaType.GraphQL.asRight)

  test("an equal q value gives the GraphQL media type"):
    assertEquals(negotiate(s"$LegacyJson, $GraphQLJson"), ResponseMediaType.GraphQL.asRight)

  test("the higher q value wins"):
    assertEquals(negotiate(s"$GraphQLJson;q=0.5, $LegacyJson;q=0.9"), ResponseMediaType.LegacyJson.asRight)

  test("the smallest q value above zero still counts"):
    assertEquals(negotiate(s"$GraphQLJson;q=0.001, $LegacyJson;q=0"), ResponseMediaType.GraphQL.asRight)

  test("a q value of 0 removes one media type"):
    assertEquals(negotiate(s"$GraphQLJson;q=0, $LegacyJson"), ResponseMediaType.LegacyJson.asRight)

  test("two Accept headers combine"):
    assertEquals(negotiate("text/html", LegacyJson), ResponseMediaType.LegacyJson.asRight)

  // --- no acceptable media type -----------------------------------------------

  test("an unsupported media type has no result"):
    assert(negotiate("text/html").isLeft)

  test("a q value of 0 for every supported media type has no result"):
    assert(negotiate(s"$GraphQLJson;q=0, $LegacyJson;q=0").isLeft)

  test("a wildcard with a q value of 0 has no result"):
    assert(negotiate("*/*;q=0").isLeft)

  test("the message keeps the subtype of the rejected media type"):
    assertEquals(
      message("text/html"),
      s"Unsupported 'Accept' header 'text/html'. Supported media types are '$GraphQLJson' and '$LegacyJson'."
    )

  test("the message names every rejected media type"):
    assertEquals(
      message("text/html, image/png"),
      s"Unsupported 'Accept' header 'text/html, image/png'. Supported media types are '$GraphQLJson' and '$LegacyJson'."
    )

  test("the message keeps a q value of 0"):
    assertEquals(
      message(s"$GraphQLJson;q=0, $LegacyJson;q=0"),
      s"Unsupported 'Accept' header '$GraphQLJson;q=0, $LegacyJson;q=0'. Supported media types are '$GraphQLJson' and '$LegacyJson'."
    )

  // --- the media type of a response -------------------------------------------

  test("the GraphQL media type applies to an error response"):
    assertEquals(ResponseMediaType.GraphQL.contentType(Status.BadRequest).mediaType.show, GraphQLJson)

  test("a legacy client gets the legacy media type on a 200 response"):
    assertEquals(ResponseMediaType.LegacyJson.contentType(Status.Ok).mediaType.show, LegacyJson)

  test("a legacy client gets the legacy media type on a 294 response"):
    assertEquals(ResponseMediaType.LegacyJson.contentType(ResponseMediaType.PartialSuccess).mediaType.show, LegacyJson)

  test("a legacy client gets the GraphQL media type on a 400 response"):
    assertEquals(ResponseMediaType.LegacyJson.contentType(Status.BadRequest).mediaType.show, GraphQLJson)

  test("the response declares the utf-8 charset"):
    assertEquals(ResponseMediaType.GraphQL.contentType(Status.Ok).charset, Charset.`UTF-8`.some)

  test("only the GraphQL media type carries status 294"):
    assertEquals(ResponseMediaType.GraphQL.partialSuccessStatus, ResponseMediaType.PartialSuccess)
    assertEquals(ResponseMediaType.LegacyJson.partialSuccessStatus, Status.Ok)
