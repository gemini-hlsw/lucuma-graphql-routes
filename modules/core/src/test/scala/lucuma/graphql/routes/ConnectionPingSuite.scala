// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import clue.model.StreamingMessage.FromClient
import clue.model.StreamingMessage.FromServer
import io.circe.Json
import io.circe.JsonObject
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.otel4s.trace.Tracer

/**
 * The graphql-transport-ws protocol permits a `ping` message in both directions. The receiver must
 * reply with a `pong` as soon as possible. A `ping` can arrive at any time on an open socket, so
 * the reply does not depend on the connection state.
 */
final class ConnectionPingSuite extends CatsEffectSuite:

  private type Reply = Option[Either[GraphQLWSError, FromServer]]

  given Logger[IO] = Slf4jLogger.getLoggerFromName("lucuma-graphql-routes-test")
  given Tracer[IO] = Tracer.noop[IO]

  /** A connection whose service always refuses, so that no test needs a schema. */
  private val connection: IO[(Connection[IO], Queue[IO, Reply])] =
    for
      queue <- Queue.unbounded[IO, Reply]
      conn  <- Connection[IO](_ => IO.none, queue)
    yield (conn, queue)

  test("A Ping before ConnectionInit gets a Pong reply"):
    connection.flatMap: (conn, queue) =>
      conn.receive(FromClient.Ping()) *>
        queue.take.assertEquals(FromServer.Pong().asRight.some)

  test("A Ping with a payload gets a Pong reply without a payload"):
    val payload = JsonObject("seq" -> Json.fromInt(1))
    connection.flatMap: (conn, queue) =>
      conn.receive(FromClient.Ping(payload.some)) *>
        queue.take.assertEquals(FromServer.Pong().asRight.some)

  test("A Ping does not close the connection"):
    connection.flatMap: (conn, queue) =>
      for
        _ <- conn.receive(FromClient.Ping())
        _ <- queue.take.assertEquals(FromServer.Pong().asRight.some)
        _ <- conn.receive(FromClient.Ping())
        _ <- queue.take.assertEquals(FromServer.Pong().asRight.some)
      yield ()

  test("A Pong from the client gets no reply"):
    connection.flatMap: (conn, queue) =>
      conn.receive(FromClient.Pong()) *> queue.tryTake.assertEquals(None)
