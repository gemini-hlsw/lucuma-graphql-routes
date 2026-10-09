// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.syntax.all.*
import clue.model.StreamingMessage.FromClient
import clue.model.StreamingMessage.FromServer
import io.circe.Json
import io.circe.JsonObject

import scala.concurrent.duration.*

/**
 * The graphql-transport-ws protocol permits a `ping` message in both directions. The receiver must
 * reply with a `pong` as soon as possible. A `ping` can arrive at any time on an open socket, so
 * the reply does not depend on the connection state.
 */
final class ConnectionPingSuite extends ConnectionSuite:

  private val pong: List[Reply] = List(Reply.Send(FromServer.Pong()))

  test("A Ping before ConnectionInit gets a Pong reply"):
    rawRepliesOf(1.second)(_.receive(ping)).assertEquals(pong)

  test("A Ping with a payload gets a Pong reply without a payload"):
    val payload = JsonObject("seq" -> Json.fromInt(1))
    rawRepliesOf(1.second)(_.receive(FromClient.Ping(payload.some))).assertEquals(pong)

  test("A Pong from the client gets no reply"):
    rawRepliesOf(1.second)(_.receive(FromClient.Pong())).assertEquals(Nil)
