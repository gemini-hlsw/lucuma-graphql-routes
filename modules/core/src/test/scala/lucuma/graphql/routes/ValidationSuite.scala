// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import clue.ResponseException
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json
import io.circe.literal.*
import natchez.Trace.Implicits.noop
import org.http4s.headers.Authorization

import BaseSuite.ClientOption
import BaseSuite.ClientOption.*

// This suite tests that validation failures are raised appropriately.

object ValidationMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query { foo: Int }
    type Subscription { bar: Int }
  """
  val typeMappings = TypeMappings.unchecked()

class ValidationSuite extends BaseSuite:

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(ValidationMapping).some.pure[IO]

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
        expected    = List(1,2,3).map { n => json"""{ "bar": $n }""" },
        variables   = None
      )

  test("[http] invalid query should report errors in GraphQL response"):
    testQuery(Http)

  test("[ws, one-off] invalid query should report errors in GraphQL response"):
    testQuery(Ws)

  test("[ws, subscription] invalid query should raise ResponseException"):
    interceptIO[ResponseException[Json]](testSubscription)

