// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import io.circe.Json
import io.circe.JsonObject
import io.circe.literal.*
import org.http4s.headers.Authorization

import scala.concurrent.duration.*

// Tests that the server sends a `Complete` message when a subscription source stream ends
// naturally, without the client initiating the close.

class SubscriptionCompleteSuite extends BaseSuite:

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(VariablesMapping).some.pure[IO]

  // VariablesMapping's Subscription.echo emits exactly 3 results and then ends.
  private val echoQuery: String    = """subscription($abc: String) { echo(s: $abc) }"""
  private val echoVars: JsonObject = Json.obj("abc" -> Json.fromString("foo")).asObject.get
  private val expected: List[Json] = List.fill(3)(json"""{ "echo": "foo" }""")

  test("server sends Complete when subscription source stream ends naturally"):
    // We deliberately do NOT call the subscription cleanup (i.e., we never send
    // client→server Complete).  The stream must terminate on its own because the
    // server sends the Complete message.  Without the fix this times out.
    openSubscription(none, echoQuery, echoVars.some).use: (sub, _) =>
      sub.compile.toList
        .timeout(5.seconds)
        .assertEquals(expected)

  test("id is removed from map after natural completion: client cleanup is a no-op"):
    // After the stream ends naturally and the server sends Complete (removing the id),
    // calling the client cleanup should be a harmless no-op (id is no longer in the
    // server-side map, so no duplicate Complete is produced).
    openSubscription(none, echoQuery, echoVars.some).use: (sub, cleanup) =>
      for
        obt <- sub.compile.toList.timeout(5.seconds)
        _   <- cleanup
      yield assertEquals(obt.map(_.spaces2), expected.map(_.spaces2))

  test("server sends Complete for an immediately-finishing (empty) subscription stream"):
    // Empty source stream: the fiber can complete before `subscriptions.update` inserts
    // the entry (the start-before-insert race).  Without the fix, no Complete is ever sent
    // and the client stream hangs; additionally a stale entry leaks in the map.
    openSubscription(none, "subscription { empty }", none).use: (sub, _) =>
      sub.compile.toList
        .timeout(5.seconds)
        .assertEquals(List.empty[Json])

  test("explicit client Complete still works after the stream already ended"):
    // The echo stream ends before the helper calls the client cleanup, so this covers the
    // ordinary path: the server already sent Complete, and the late cleanup changes nothing.
    subscription(
      bearerToken = none,
      query       = echoQuery,
      mutations   = Right(IO.unit),
      variables   = echoVars.some,
    ).assertEquals(expected)

  test("explicit client Complete interrupts a still-running subscription"):
    // The `ticks` source stream never ends, so the client cleanup runs while the subscription
    // is live.  This covers the interruptWhen path: remove() takes the map entry, sends
    // Complete, and cancels the fiber.  The stream finalizer must not send a second Complete.
    subscription(
      bearerToken = none,
      query       = "subscription { ticks }",
      mutations   = Right(IO.unit),
      variables   = none,
    ).timeout(5.seconds).map: obt =>
      assert(obt.nonEmpty, "expected at least one tick before the client cleanup ran")
      assertEquals(obt.map(_.spaces2).distinct, List(json"""{ "ticks": "tick" }""".spaces2))
