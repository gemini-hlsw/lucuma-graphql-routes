// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import clue.model.StreamingMessage.FromClient
import clue.model.json.given
import io.circe.parser.decode
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.FiniteDuration

/**
 * A suite that sends client messages to an in-process connection on the virtual clock, and asserts
 * on the replies. There is no socket and no server.
 */
abstract class ConnectionSuite extends CatsEffectSuite:

  given Logger[IO] = BaseSuite.logger
  given Tracer[IO] = Tracer.noop[IO]

  /** Decodes a client message from its wire format. */
  protected def fromClient(s: String): FromClient =
    decode[FromClient](s).fold(throw _, identity)

  protected val init: FromClient = FromClient.ConnectionInit()

  protected val ping: FromClient = FromClient.Ping()

  protected def complete(id: String): FromClient = FromClient.Complete(id)

  /** The service that the connection authorizes against. */
  protected val testService: IO[Option[GraphQLService[IO]]] =
    GraphQLService(TestMapping).some.pure[IO]

  // Runs the script against a connection that received no `connection_init` message, then returns
  // every reply that the connection made, after the given settle time. Use this to test the
  // handshake itself. For everything else, use `repliesOf`.
  protected def rawRepliesOf(
    settle:  FiniteDuration,
    service: IO[Option[GraphQLService[IO]]] = testService
  )(script: Connection[IO] => IO[Unit]): IO[List[Reply]] =
    TestControl.executeEmbed:
      BaseSuite
        .connectionResource(_ => service)
        .use: (conn, queue) =>
          script(conn) *>
            IO.sleep(settle) *>
            queue.tryTakeN(none)

  // Runs the script against an initialized connection on the virtual clock, then returns every
  // reply that the connection made, after the given settle time. A script can sleep between
  // messages, so a test can put a client message after the result of an operation.
  protected def repliesOf(
    settle: FiniteDuration
  )(script: Connection[IO] => IO[Unit]): IO[List[Reply]] =
    rawRepliesOf(settle)(conn => conn.receive(init) *> script(conn))

  // Sends the messages to an initialized connection on the virtual clock, then returns every
  // reply that the connection made, after the given settle time.
  protected def repliesAfter(settle: FiniteDuration)(ms: FromClient*): IO[List[Reply]] =
    repliesOf(settle)(conn => ms.toList.traverse_(conn.receive))
