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

  // Runs the script against an initialized connection on the virtual clock, then returns every
  // reply that the connection made, after the given settle time. A script can sleep between
  // messages, so a test can put a client message after the result of an operation.
  protected def repliesOf(
    settle: FiniteDuration
  )(script: Connection[IO] => IO[Unit]): IO[List[Reply]] =
    TestControl.executeEmbed:
      BaseSuite
        .connectionResource(_ => GraphQLService(TestMapping).some.pure[IO])
        .use: (conn, queue) =>
          conn.receive(init) *>
            script(conn) *>
            IO.sleep(settle) *>
            queue.tryTakeN(none)

  // Sends the messages to an initialized connection on the virtual clock, then returns every
  // reply that the connection made, after the given settle time.
  protected def repliesAfter(settle: FiniteDuration)(ms: FromClient*): IO[List[Reply]] =
    repliesOf(settle)(conn => ms.toList.traverse_(conn.receive))
