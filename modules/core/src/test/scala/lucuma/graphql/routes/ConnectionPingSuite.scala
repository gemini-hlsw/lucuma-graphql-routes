// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import cats.effect.Resource
import cats.effect.std.Queue
import cats.syntax.all.*
import clue.model.StreamingMessage.FromClient
import clue.model.StreamingMessage.FromServer
import io.circe.Json
import io.circe.JsonObject
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.otel4s.trace.Tracer

/**
 * The graphql-transport-ws protocol permits a `ping` message in both directions. The receiver must
 * reply with a `pong` as soon as possible. A `ping` can arrive at any time on an open socket, so
 * the reply does not depend on the connection state.
 */
final class ConnectionPingSuite extends CatsEffectSuite:

  given Logger[IO] = BaseSuite.logger
  given Tracer[IO] = Tracer.noop[IO]

  /** A connection whose service always refuses, so that no test needs a schema. */
  private val connection: Resource[IO, (Connection[IO], Queue[IO, Reply])] =
    BaseSuite.connectionResource(_ => IO.none)

  test("A Ping before ConnectionInit gets a Pong reply"):
    connection.use: (conn, queue) =>
      conn.receive(FromClient.Ping()) *>
        queue.take.assertEquals(Reply.Send(FromServer.Pong()))

  test("A Ping with a payload gets a Pong reply without a payload"):
    val payload = JsonObject("seq" -> Json.fromInt(1))
    connection.use: (conn, queue) =>
      conn.receive(FromClient.Ping(payload.some)) *>
        queue.take.assertEquals(Reply.Send(FromServer.Pong()))

  test("A Ping does not close the connection"):
    connection.use: (conn, queue) =>
      for
        _ <- conn.receive(FromClient.Ping())
        _ <- queue.take.assertEquals(Reply.Send(FromServer.Pong()))
        _ <- conn.receive(FromClient.Ping())
        _ <- queue.take.assertEquals(Reply.Send(FromServer.Pong()))
      yield ()

  test("A Pong from the client gets no reply"):
    connection.use: (conn, queue) =>
      conn.receive(FromClient.Pong()) *> queue.tryTake.assertEquals(None)
