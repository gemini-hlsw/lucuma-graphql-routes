// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import cats.syntax.all.*
import clue.model.StreamingMessage.FromClient

import scala.concurrent.duration.*

/**
 * The graphql-transport-ws protocol reserves close code 4409 for a `subscribe` message that uses
 * an id that is already active. This suite checks the path from the client message to the reply
 * queue of the connection.
 */
final class DuplicateSubscriptionIdSuite extends ConnectionSuite:

  // The `ticks` source stream never ends, so the subscription stays active.
  private def subscribe(id: String): FromClient =
    fromClient(s"""{"id":"$id","type":"subscribe","payload":{"query":"subscription { ticks }"}}""")

  private def replies(ms: FromClient*): IO[List[Reply]] =
    repliesAfter(1.second)(ms*)

  private val alreadyExists: Reply =
    Reply.CloseWith(GraphQLWSError.SubscriberAlreadyExists("1"))

  test("a second subscribe with an active id closes the socket with code 4409"):
    replies(subscribe("1"), subscribe("1")).map: obt =>
      assert(obt.contains(alreadyExists), s"expected a 4409 close request, got $obt")

  test("a second subscribe with an active id ends the reply stream"):
    replies(subscribe("1"), subscribe("1")).map: obt =>
      assertEquals(obt.lastOption, alreadyExists.some, s"the close was not the last reply: $obt")

  test("a message that arrives after the close is ignored"):
    replies(subscribe("1"), subscribe("1"), complete("2"), subscribe("3"), init).map: obt =>
      assertEquals(obt.lastOption, alreadyExists.some, s"the close was not the last reply: $obt")

  test("a subscribe with a free id does not close the socket"):
    replies(subscribe("1"), subscribe("2")).map: obt =>
      assert(!obt.contains(alreadyExists), s"the second id closed the socket, got $obt")

  test("an id is free again after the client sends complete"):
    replies(subscribe("1"), complete("1"), subscribe("1")).map: obt =>
      assert(!obt.contains(alreadyExists), s"the reused id closed the socket, got $obt")
