// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import clue.model.StreamingMessage.FromClient
import clue.model.StreamingMessage.FromServer
import clue.model.json.given
import fs2.Stream
import io.circe.Json
import io.circe.JsonObject
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.client.websocket.*
import org.http4s.headers.Authorization
import org.http4s.websocket.WebSocketFrame

import scala.concurrent.duration.*

/**
 * The server sends a `ping` frame on the keepalive interval, and the client protocol layer
 * replies with a `pong`. A whole interval without a `pong` marks a dead client, so the server
 * closes the socket.
 */
final class HeartbeatSuite extends BaseSuite:

  // The interval is short, so that the socket test runs in milliseconds.
  private val interval: FiniteDuration =
    100.millis

  // The unit tests drive the heartbeat of this handler on a virtual clock, and the socket tests
  // connect to the routes that `BaseSuite` serves with the same interval.
  private val handler: WsRouteHandler[IO] =
    new WsRouteHandler[IO](service, interval)

  override protected def keepAlive: FiniteDuration = interval

  // The authorization lookup of this token takes several intervals. A slow lookup is a supported
  // case, which `ConnectionInitTimeoutSuite` also covers. It blocks the handler of the client
  // messages, so it must not delay the `pong` frames of the heartbeat.
  private val slowToken: String =
    "slow"

  override def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    if auth.exists(_.credentials.renderString.contains(slowToken))
    then IO.sleep(interval * 4).as(GraphQLService(TestMapping).some)
    else IO.none

  // The travel time of a `pong` reply, from the `ping` at the client to the server.
  private val roundTrip: FiniteDuration =
    interval / 5

  // Runs the heartbeat on the virtual clock. `write` sets the duration of one socket write,
  // `reply` states whether the client answers each `ping`, and `stall` makes the socket take
  // the first frame and no other. Returns the time of each frame, and the close time, if any.
  private def run(
    over:  FiniteDuration,
    write: FiniteDuration = Duration.Zero,
    reply: Boolean = true,
    stall: Boolean = false
  ): IO[(List[(FiniteDuration, WebSocketFrame)], Option[FiniteDuration])] =
    TestControl.executeEmbed:
      for
        start        <- IO.monotonic
        closedAt     <- Ref.of[IO, Option[FiniteDuration]](none)
        pongReceived <- Ref.of[IO, Boolean](false)
        stalled      <- Deferred[IO, Either[Throwable, Unit]]
        since         = IO.monotonic.map(_ - start)
        close         = since.flatMap(t => closedAt.set(t.some))
        pong          = (IO.sleep(roundTrip) *> pongReceived.set(true)).start.void
        obt          <- handler
                          .heartbeat(close, stalled, pongReceived)
                          .evalTap(_ => IO.sleep(write))
                          .evalTap(_ => IO.whenA(reply)(pong))
                          .evalMap(f => since.tupleRight(f))
                          .evalTap(_ => IO.whenA(stall)(IO.never))
                          .interruptAfter(over)
                          .compile
                          .toList
        end          <- closedAt.get
      yield (obt, end)

  test("a client that replies to every ping gets one ping per interval and stays connected"):
    run(over = interval * 7 / 2).map: (obt, closedAt) =>
      assertEquals(obt, List(interval, interval * 2, interval * 3).tupleRight(WsRouteHandler.PingFrame))
      assertEquals(closedAt, none, "a live client was closed")

  // The first `ping` goes out one interval in, and its check runs one interval after that. The
  // check finds no reply and closes the connection.
  test("a client that replies to no ping is closed after the pong timeout"):
    run(over = interval * 5, reply = false).map: (obt, closedAt) =>
      assert(obt.nonEmpty, "the heartbeat sent no ping")
      assertEquals(closedAt, (interval * 2).some, "the close came at the wrong time")

  // A slow socket write delays the `ping` frame and the `pong` reply that follows it. The cycle
  // runs on its own fiber, so the write does not delay the check, and the reply does not count
  // as missed while the write and the reply together stay inside one interval.
  test("a stalled socket write does not close a client that replies in time"):
    run(over = interval * 6, write = interval * 7 / 10).map: (_, closedAt) =>
      assertEquals(closedAt, none, "a stalled write closed a live client")

  // A socket that takes no frames is dead, even if its last reply was on time. The next `ping`
  // hand-off has the same one-interval limit, so the cycle aborts the connection when it passes.
  // The client still answers the first `ping`, so the cycle reaches that hand-off.
  test("a socket that stops taking frames is closed after the hand-off limit"):
    run(over = interval * 5, stall = true).map: (_, closedAt) =>
      assertEquals(closedAt, (interval * 3).some, "the abort came at the wrong time")

  private def wsConnection: Resource[IO, WSConnectionHighLevel[IO]] =
    Resource.suspend:
      IO(wsClientFixture().connectHighLevel(wsRequest(serverFixture())))

  private def text(m: FromClient): WSFrame.Text =
    WSFrame.Text(m.asJson.noSpaces)

  // The first server message on the socket. `act` runs alongside the read. A closed socket
  // sends no message, so a message proves an open socket. The client answers `ping` frames only
  // while it reads, so the read must run for the whole test.
  private def firstMessage(conn: WSConnectionHighLevel[IO])(act: IO[Unit]): IO[Option[FromServer]] =
    conn
      .receiveStream
      .concurrently(Stream.exec(act))
      .collectFirst { case WSFrame.Text(s, _) => decode[FromServer](s).toOption }
      .compile
      .last
      .map(_.flatten)
      // The read fails on a closed socket, which counts as no message.
      .handleError(_ => none)
      .timeoutTo(interval * 10, IO.none)

  // The client replies to the `ping` frames, as the WebSocket protocol requires, so the socket
  // stays open across several intervals and still answers a `ping` message of the client.
  test("a socket stays open while the client answers the ping frames"):
    wsConnection.use: conn =>
      firstMessage(conn)(IO.sleep(interval * 4) *> conn.send(text(FromClient.Ping())))
        .assertEquals(FromServer.Pong().some)

  // The authorization lookup blocks the handler of the client messages for four intervals, which
  // is longer than the limit for the `pong` reply. The reader records the replies of the client,
  // so the heartbeat still sees a live client and the acknowledgement arrives.
  test("a slow authorization lookup does not close a live socket"):
    val props = JsonObject("Authorization" -> Json.fromString(s"Bearer $slowToken"))
    wsConnection.use: conn =>
      firstMessage(conn)(conn.send(text(FromClient.ConnectionInit(props.some))))
        .assertEquals(FromServer.ConnectionAck().some)
