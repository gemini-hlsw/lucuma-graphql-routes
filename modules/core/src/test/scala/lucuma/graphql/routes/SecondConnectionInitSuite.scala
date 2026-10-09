// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import cats.syntax.all.*
import clue.model.StreamingMessage.FromClient

import scala.concurrent.duration.*

/**
 * The graphql-transport-ws protocol reserves close code 4429 for a second `connection_init`
 * message. The protocol has no re-authentication feature, so the second message is an error.
 */
final class SecondConnectionInitSuite extends ConnectionSuite:

  private val tooManyInits: Reply =
    Reply.CloseWith(GraphQLWSError.TooManyInitializationRequests)

  private def replies(ms: FromClient*): IO[List[Reply]] =
    repliesAfter(1.second)(ms*)

  test("a second connection_init closes the socket with code 4429 and is not acknowledged"):
    replies(init).map: obt =>
      assertEquals(obt.lastOption, tooManyInits.some, s"expected a 4429 close request, got $obt")
      assertEquals(obt.count(_ == ack), 1, s"the second connection_init was acknowledged, got $obt")

  test("a message that arrives after the close is ignored"):
    replies(init, subscribe("1"), complete("1"), init).map: obt =>
      assertEquals(obt.lastOption, tooManyInits.some, s"the close was not the last reply: $obt")

  test("an active subscription stops when the second connection_init arrives"):
    repliesOf(1.second): conn =>
      conn.receive(subscribe("1")) *> IO.sleep(100.milliseconds) *> conn.receive(init)
    .map: obt =>
      assertEquals(obt.lastOption, tooManyInits.some, s"the subscription outlived the close: $obt")
