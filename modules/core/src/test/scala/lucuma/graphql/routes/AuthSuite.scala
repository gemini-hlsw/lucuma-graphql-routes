// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import org.http4s.headers.Authorization
import org.http4s.client.UnexpectedStatus
import BaseSuite.ClientOption
import BaseSuite.ClientOption.*
import org.http4s.Credentials
import org.http4s.AuthScheme
import io.circe.Json
import io.circe.literal.*
import clue.DisconnectedException
import edu.gemini.grackle.syntax.*
import natchez.Trace.Implicits.noop
import edu.gemini.grackle.circe.CirceMapping
import edu.gemini.grackle.Result
import fs2.Stream

// This suite tests that authorization headers are getting through correctly, and that
// missing/rejected credentials result in http 403 errors (or disconnection for websockets).

object AuthMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query { foo: Int }
    type Subscription { bar: Int }
  """
  val QueryType = schema.ref("Query")
  val SubscriptionType = schema.ref("Subscription")
  val typeMappings: List[TypeMapping] =
    List(
      ObjectMapping(QueryType, List(
        CursorFieldJson("foo", _ => Result.success(Json.fromInt(42)), Nil)
      )),
      ObjectMapping(SubscriptionType, List(
        RootStream.computeJson("bar"): (path, env) =>
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

  test("[http] Missing credentials should raise UnexpectedStatus."):
    interceptIO[UnexpectedStatus](testQuery(None, Http))

  test("[http] Incorrect credentials should raise UnexpectedStatus."):
    interceptIO[UnexpectedStatus](testQuery(Some("steve"), Http))

  test("[http] Correct credentials should work."):
    testQuery(Some("bob"), Http)

  test("[ws, one-off] Missing credentials should raise DisconnectedException."):
    interceptIO[DisconnectedException](testQuery(None, Ws))

  test("[ws, one-off] Incorrect credentials should raise DisconnectedException."):
    interceptIO[DisconnectedException](testQuery(Some("steve"), Ws))

  test("[ws, one-off] Correct credentials should work."):
    testQuery(Some("bob"), Ws)
  
  test("[ws, subscription] Missing credentials should raise DisconnectedException."):
    interceptIO[DisconnectedException](testSubscription(None))

  test("[ws, subscription] Incorrect credentials should raise DisconnectedException."):
    interceptIO[DisconnectedException](testSubscription(Some("steve")))

  test("[ws, subscription] Correct credentials should work."):
    testSubscription(Some("bob"))
