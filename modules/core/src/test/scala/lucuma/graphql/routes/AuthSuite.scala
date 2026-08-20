// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import clue.RemoteInitializationException
import fs2.Stream
import grackle.Result
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json
import io.circe.literal.*
import io.circe.parser
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.Method
import org.http4s.Request
import org.http4s.Status
import org.http4s.circe.*
import org.http4s.headers.Authorization
import org.typelevel.ci.*

import BaseSuite.ClientOption
import BaseSuite.ClientOption.*

// This suite tests that authorization headers are getting through correctly, and that
// missing/rejected credentials result in http 403 errors (or disconnection for websockets).

object AuthMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query { foo: Int }
    type Subscription { bar: Int }
  """
  val QueryType        = schema.ref("Query")
  val SubscriptionType = schema.ref("Subscription")
  val typeMappings     = List(
    ObjectMapping(QueryType, List(
      CursorFieldJson("foo", _ => Result.success(Json.fromInt(42)), Nil)
    )),
    ObjectMapping(SubscriptionType, List(
      RootStream.computeJson("bar"): (_, _) =>
        Stream(1, 2, 3).covary[IO].map(n => Result.success(Json.fromInt(n)))
    ))
  )

class AuthSuite extends BaseSuite:

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    auth match
      case Some(Authorization(Credentials.Token(AuthScheme.Bearer, "bob"))) => IO(GraphQLService(AuthMapping).some)
      case _ => none.pure[IO]

  def testQuery(bearerToken: Option[String], option: ClientOption): IO[Unit] =
    expect(
      bearerToken = bearerToken,
      query       = "query { foo }",
      expected    = Right(json"""{ "foo": 42 }"""),
      variables   = None,
      client      = option
    )

  def testSubscription(bearerToken: Option[String]): IO[Unit] =
    subscriptionExpect(
      bearerToken = bearerToken,
      query       = "subscription { bar }",
      mutations   = Right(IO.unit),
      expected    = List(1,2,3).map { n => json"""{ "bar": $n }""" },
      variables   = None
    )

  // The 403 response carries a well-formed GraphQL response with the GraphQL media type, so the
  // client reads the body and reports the errors in it.
  test("[http] Missing credentials should raise ResponseException."):
    interceptGraphQL("Access denied.")(testQuery(None, Http))

  test("[http] Incorrect credentials should raise ResponseException."):
    interceptGraphQL("Access denied.")(testQuery(Some("steve"), Http))

  test("[http] Missing credentials give 403 with the GraphQL media type and an errors body."):
    rawResponse(uri => Request[IO](Method.POST, uri).withEntity(json"""{"query": "query { foo }"}"""))
      .map: (status, headers, body) =>
        assertEquals(status, Status.Forbidden)
        val contentType = headers.get(ci"Content-Type").map(_.head.value)
        assert(contentType.exists(_.startsWith("application/graphql-response+json")), s"Got: $contentType")
        val errors = parser.parse(body).toOption.flatMap(_.hcursor.downField("errors").as[List[Json]].toOption)
        assertEquals(errors.map(_.size), Some(1), body)

  test("[http] Correct credentials should work."):
    testQuery(Some("bob"), Http)

  test("[ws, one-off] Missing credentials should raise RemoteInitializationException."):
    interceptIO[RemoteInitializationException](testQuery(None, Ws))

  test("[ws, one-off] Incorrect credentials should raise RemoteInitializationException."):
    interceptIO[RemoteInitializationException](testQuery(Some("steve"), Ws))

  test("[ws, one-off] Correct credentials should work."):
    testQuery(Some("bob"), Ws)

  test("[ws, subscription] Missing credentials should raise RemoteInitializationException."):
    interceptIO[RemoteInitializationException](testSubscription(None))

  test("[ws, subscription] Incorrect credentials should raise RemoteInitializationException."):
    interceptIO[RemoteInitializationException](testSubscription(Some("steve")))

  test("[ws, subscription] Correct credentials should work."):
    testSubscription(Some("bob"))
