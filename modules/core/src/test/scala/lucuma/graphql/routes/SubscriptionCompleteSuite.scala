// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import clue.ResponseException
import io.circe.Json
import io.circe.JsonObject
import io.circe.literal.*

import scala.concurrent.duration.*

// The end of a subscription over a socket: the source stream ends, the client sends `complete`,
// or the source stream fails. `SubscriptionsSuite` covers the same paths without a socket.
class SubscriptionCompleteSuite extends BaseSuite:

  val graphQLService: GraphQLService[IO] =
    GraphQLService.unvalidated(TestMapping)

  // TestMapping's Subscription.echo emits exactly 3 results and then ends.
  private val echoQuery: String    = """subscription($abc: String) { echo(s: $abc) }"""
  private val echoVars: JsonObject = Json.obj("abc" -> Json.fromString("foo")).asObject.get
  private val expected: List[Json] = List.fill(3)(json"""{ "echo": "foo" }""")

  test("server sends Complete when the source stream ends, and a later client Complete is a no-op"):
    // The client sends no `complete` before the stream ends, so the stream can only end because
    // the server sends `Complete`. The id is gone from the server-side map by then, so the late
    // client cleanup changes nothing.
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

  test("a failure of the source stream gives the client the results so far, then an error message"):
    for
      errorRef <- IO.ref(Option.empty[ResponseException[Json]])
      results  <- subscription(
                    bearerToken = none,
                    query       = "subscription { failing }",
                    mutations   = Right(IO.unit),
                    variables   = none,
                    onError     = e => errorRef.set(Some(e))
                  )
      err      <- errorRef.get
    yield
      assertEquals(results, List(json"""{"failing":"first"}""", json"""{"failing":"second"}"""))
      assert(err.isDefined, "expected a ResponseException to be delivered via onError")
      assert(err.exists(_.errors.head.message.contains("Internal Error")), s"unexpected error content: $err")
