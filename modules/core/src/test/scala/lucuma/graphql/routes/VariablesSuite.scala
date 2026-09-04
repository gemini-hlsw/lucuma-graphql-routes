// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import io.circe.Json
import io.circe.literal.*
import org.http4s.headers.Authorization

import BaseSuite.ClientOption
import BaseSuite.ClientOption.*

// This suite tests that variables make it through.

class VariablesSuite extends BaseSuite:
  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(TestMapping).some.pure[IO]

  def testQuery(option: ClientOption): IO[Unit] =
    expect(
      bearerToken = none,
      query       = """query($abc: String) { echo(s: $abc) }""",
      expected    = Right(json"""{ "echo": "foo" }"""),
      variables   = Json.obj("abc" -> Json.fromString("foo")).asObject,
      client      = option
    )

  def testSubscription: IO[Unit] =
    subscriptionExpect(
      bearerToken = none,
      query       = """subscription($abc: String) { echo(s: $abc) }""",
      mutations   = Right(IO.unit),
      expected    = List.fill(3)(json"""{ "echo": "foo" }"""),
      variables   = Json.obj("abc" -> Json.fromString("foo")).asObject,
    )

  test("[http] Variables should be passed."):
    testQuery(Http)

  test("[ws, one-off] Variables should be passed."):
    testQuery(Ws)

  test("[ws, subscription] Variables should be passed."):
    testSubscription
