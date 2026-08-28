// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import cats.syntax.all.*
import clue.model.StreamingMessage.FromServer

import scala.concurrent.duration.*

/**
 * The graphql-transport-ws protocol reserves close code 4408 for a connection that sends no
 * `connection_init` message before the wait time passes. This suite runs the connection on the
 * virtual clock and reads the replies straight off the queue.
 */
final class ConnectionInitTimeoutSuite extends ConnectionSuite:

  private val timeoutClose: Reply =
    Reply.CloseWith(GraphQLWSError.InitializationTimeout)

  private val ack: Reply =
    Reply.Send(FromServer.ConnectionAck())

  private val beforeExpiry: FiniteDuration = Connection.ConnectionInitWaitTimeout - 1.second
  private val afterExpiry: FiniteDuration  = Connection.ConnectionInitWaitTimeout + 1.second

  test("no connection_init before the timeout closes the socket with code 4408"):
    rawRepliesOf(afterExpiry)(_ => IO.unit).map: obt =>
      assertEquals(obt.lastOption, timeoutClose.some, s"expected a 4408 close request, got $obt")

  test("the timeout close does not arrive before the wait time passes"):
    rawRepliesOf(beforeExpiry)(_ => IO.unit).map: obt =>
      assert(!obt.contains(timeoutClose), s"the timer fired early, got $obt")

  test("a timely connection_init prevents the 4408 close"):
    rawRepliesOf(afterExpiry)(_.receive(init)).map: obt =>
      assert(!obt.contains(timeoutClose), s"the timer fired after connection_init, got $obt")

  test("a connection_init that arrives after the timeout is ignored"):
    rawRepliesOf(1.second): conn =>
      IO.sleep(afterExpiry) *> conn.receive(init)
    .map: obt =>
      assert(!obt.contains(ack), s"a late connection_init was acknowledged, got $obt")

  test("slow authorization after a timely connection_init does not cause a 4408 close"):
    val slow = IO.sleep(Connection.ConnectionInitWaitTimeout * 2) *> testService
    rawRepliesOf(Connection.ConnectionInitWaitTimeout * 3, slow)(_.receive(init)).map: obt =>
      assert(!obt.contains(timeoutClose), s"slow authorization was reported as a 4408, got $obt")
      assert(obt.contains(ack), s"the connection was not acknowledged, got $obt")

  test("a close from the client prevents a later 4408 close"):
    rawRepliesOf(afterExpiry)(_.close).map: obt =>
      assert(!obt.contains(timeoutClose), s"the timer fired after close, got $obt")
