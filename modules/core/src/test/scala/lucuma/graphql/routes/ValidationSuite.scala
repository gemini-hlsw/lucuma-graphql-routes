// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import clue.ResponseException
import io.circe.Json

import BaseSuite.ClientOption
import BaseSuite.ClientOption.*

// This suite tests that validation failures are raised appropriately.

class ValidationSuite extends BaseSuite:

  val graphQLService: GraphQLService[IO] =
    GraphQLService.unvalidated(TestMapping)

  def testQuery(option: ClientOption): IO[Unit] =
    expect(
      bearerToken = None,
      query       = "query { x }",
      expected    = Left(List("No field 'x' for type Query")),
      variables   = None,
      client      = option
    )

  def testSubscription: IO[Unit] =
      subscriptionExpect(
        bearerToken = None,
        query       = "subscription { x }",
        mutations   = Right(IO.unit),
        expected    = Nil,
        variables   = None
      )

  test("[http] invalid query should report errors in GraphQL response"):
    testQuery(Http)

  test("[ws, one-off] invalid query should report errors in GraphQL response"):
    testQuery(Ws)

  test("[ws, subscription] invalid query should raise ResponseException"):
    interceptIO[ResponseException[Json]](testSubscription)
