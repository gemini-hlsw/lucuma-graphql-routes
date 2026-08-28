// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.effect.std.Supervisor
import cats.effect.testkit.TestControl
import cats.implicits.*
import cats.~>
import clue.model.StreamingMessage.FromServer
import clue.model.StreamingMessage.FromServer.*
import fs2.Stream
import grackle.Result
import io.circe.Json
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.*

class SubscriptionsSuite extends CatsEffectSuite:

  given Logger[IO] = BaseSuite.logger

  private val settle: IO[Unit] =
    IO.sleep(1.second)

  // A short name for each message, so that a test can assert on the whole sequence.
  private def label(m: FromServer): String = m match
    case Next(id, _)             => s"next:$id"
    case FromServer.Error(id, _) => s"error:$id"
    case Complete(id)            => s"complete:$id"
    case other                   => other.toString

  private def recorder: IO[(Ref[IO, List[String]], FromServer => IO[Unit])] =
    Ref[IO]
      .of(List.empty[String])
      .map(ref => (ref, msg => ref.update(_ :+ label(msg))))

  private val ok: Result[Json] = Result(Json.fromString("ok"))

  private def boom: Throwable = new RuntimeException("boom")

  private val delayingLogger: Logger[IO] =
    BaseSuite.logger.mapK(new (IO ~> IO):
      def apply[A](fa: IO[A]): IO[A] = IO.sleep(200.milliseconds) *> fa)

  // Runs one test program on the virtual clock, inside a Supervisor scope for the subscriptions.
  // `log` reads the labels of the messages that the subscriptions sent so far.
  private def run(io: (Subscriptions[IO], IO[List[String]]) => IO[Unit])(using Logger[IO]): IO[Unit] =
    TestControl.executeEmbed:
      Supervisor[IO].use: sup =>
        for
          (rec, send) <- recorder
          subs        <- Subscriptions[IO](sup, send)
          _           <- io(subs, rec.get)
        yield ()

  test("a stream that ends before the map entry exists still produces a Complete"):
    given Logger[IO] = delayingLogger
    run: (subs, log) =>
      for
        _   <- subs.add("1", Stream(ok).covary[IO])
        _   <- settle
        obt <- log
      yield assertEquals(obt, List("next:1", "complete:1"))

  test("a cancelled add leaves no subscription that removeAll cannot stop"):
    given Logger[IO] = delayingLogger
    run: (subs, log) =>
      for
        f   <- subs.add("1", Stream.awakeEvery[IO](25.milliseconds).as(ok)).start
        _   <- IO.sleep(100.milliseconds)
        _   <- f.cancel
        _   <- subs.removeAll
        obt <- log
      yield assertEquals(obt.count(_ === "complete:1"), 1)

  test("a stream that ends naturally produces one Complete after the results"):
    run: (subs, log) =>
      for
        _   <- subs.add("1", Stream(ok, ok).covary[IO])
        _   <- settle
        obt <- log
      yield assertEquals(obt, List("next:1", "next:1", "complete:1"))

  test("a stream that fails sends an Error after the results, and no Complete"):
    run: (subs, log) =>
      for
        _   <- subs.add("1", Stream(ok, ok).covary[IO] ++ Stream.raiseError[IO](boom))
        _   <- settle
        obt <- log
      yield assertEquals(obt, List("next:1", "next:1", "error:1"))

  test("a stream that fails before the map entry exists still produces an Error"):
    given Logger[IO] = delayingLogger
    run: (subs, log) =>
      for
        _   <- subs.add("1", Stream.raiseError[IO](boom))
        _   <- settle
        // A leaked entry would make one of these send a Complete for a dead subscription.
        _   <- subs.remove("1")
        _   <- subs.removeAll
        _   <- settle
        obt <- log
      yield assertEquals(obt, List("error:1"))

  test("an Error ends the operation: no Next and no Complete follow it"):
    // The protocol makes Error terminal for an id. The stream must end after the first Error
    // message, even when the source stream has more results.
    run: (subs, log) =>
      for
        _   <- subs.add("1", Stream(Result.failure[Json]("boom"), ok).covary[IO])
        _   <- settle
        obt <- log
      yield assertEquals(obt, List("error:1"))

  test("an id is free again as soon as an Error goes to the client"):
    run: (subs, log) =>
      for
        _   <- subs.add("1", Stream(Result.failure[Json]("boom")).covary[IO].onFinalize(IO.sleep(1.second)))
        _   <- IO.sleep(100.milliseconds)
        res <- subs.add("1", Stream(ok).covary[IO])
        _   <- settle
        obt <- log
      yield
        assert(res, "the reused id did not start an event stream")
        assertEquals(obt, List("error:1", "next:1", "complete:1"))

  test("no Complete follows an Error when the caller removes the subscription"):
    // The Error ends the stream and frees the id. A later `remove` must find nothing and must
    // not send a Complete for the ended id.
    run: (subs, log) =>
      for
        _   <- subs.add("1", Stream(Result.failure[Json]("boom")).covary[IO] ++ Stream.never[IO])
        _   <- IO.sleep(100.milliseconds)
        _   <- subs.remove("1")
        _   <- settle
        obt <- log
      yield assertEquals(obt, List("error:1"))

  test("no Complete follows an Error when removeAll stops the subscription"):
    run: (subs, log) =>
      for
        _   <- subs.add("1", Stream(Result.failure[Json]("boom")).covary[IO] ++ Stream.never[IO])
        _   <- IO.sleep(100.milliseconds)
        _   <- subs.removeAll
        _   <- settle
        obt <- log
      yield assertEquals(obt, List("error:1"))

  test("remove on a running subscription sends no Complete"):
    run: (subs, log) =>
      for
        _   <- subs.add("1", Stream.awakeEvery[IO](25.milliseconds).as(ok))
        _   <- IO.sleep(100.milliseconds)
        _   <- subs.remove("1")
        _   <- settle
        obt <- log
      yield
        assert(obt.count(_ === "next:1") >= 1, "the stream sent nothing before remove")
        assertEquals(obt.count(_ === "complete:1"), 0)

  test("a subscribe with an id that is active reports a duplicate and sends nothing"):
    run: (subs, log) =>
      for
        _   <- subs.add("1", Stream.never[IO])
        res <- subs.add("1", Stream(ok).covary[IO])
        _   <- settle
        obt <- log
      yield
        assert(!res, "the duplicate id started an event stream")
        assertEquals(obt, Nil)

  test("a duplicate subscribe does not cancel or replace the active subscription"):
    run: (subs, log) =>
      for
        _      <- subs.add("1", Stream.awakeEvery[IO](25.milliseconds).as(ok))
        _      <- IO.sleep(100.milliseconds)
        // The duplicate stream would send an Error if it started.
        _      <- subs.add("1", Stream(Result.failure[Json]("dup")).covary[IO])
        before <- log.map(_.count(_ === "next:1"))
        _      <- IO.sleep(100.milliseconds)
        after  <- log.map(_.count(_ === "next:1"))
        _      <- subs.removeAll
        _      <- settle
        obt    <- log
      yield
        assert(after > before, "the duplicate subscribe stopped the active stream")
        assert(!obt.contains("error:1"), "the duplicate stream started")
        assertEquals(obt.count(_ === "complete:1"), 1)

  test("an id is free again after its stream ends"):
    run: (subs, log) =>
      for
        _   <- subs.add("1", Stream(ok).covary[IO])
        _   <- settle
        _   <- subs.add("1", Stream(ok).covary[IO])
        _   <- settle
        obt <- log
      yield assertEquals(obt, List("next:1", "complete:1", "next:1", "complete:1"))

  test("an id is free again after remove"):
    run: (subs, _) =>
      for
        _   <- subs.add("1", Stream.awakeEvery[IO](25.milliseconds).as(ok))
        _   <- IO.sleep(100.milliseconds)
        _   <- subs.remove("1")
        res <- subs.add("1", Stream(ok).covary[IO])
        _   <- settle
      yield assert(res, "the id was not free after remove")
