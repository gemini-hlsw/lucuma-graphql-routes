// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
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

  // Runs one test program on the virtual clock.
  private def run(io: IO[Unit]): IO[Unit] =
    TestControl.executeEmbed(io)

  private val settle: IO[Unit] =
    IO.sleep(1.second)

  // A short name for each message, so that a test can assert on the whole sequence.
  private def label(m: FromServer): String = m match
    case Next(id, _)             => s"next:$id"
    case FromServer.Error(id, _) => s"error:$id"
    case Complete(id)            => s"complete:$id"
    case other                   => other.toString

  private def recorder: IO[(Ref[IO, List[String]], Option[FromServer] => IO[Unit])] =
    Ref[IO].of(List.empty[String]).map: ref =>
      (ref, msg => msg.fold(IO.unit)(m => ref.update(_ :+ label(m))))

  private val ok: Result[Json] = Result(Json.fromString("ok"))

  private def boom: Throwable = new RuntimeException("boom")

  private val delayingLogger: Logger[IO] =
    BaseSuite.logger.mapK(new (IO ~> IO) { def apply[A](fa: IO[A]): IO[A] = IO.sleep(200.milliseconds) *> fa })

  test("a stream that ends before the map entry exists still produces a Complete"):
    given Logger[IO] = delayingLogger
    run:
      for
        (log, send) <- recorder
        subs        <- Subscriptions[IO](send)
        _           <- subs.add("1", Stream(ok).covary[IO])
        _           <- settle
        obt         <- log.get
      yield assertEquals(obt, List("next:1", "complete:1"))

  test("a cancelled add leaves no subscription that removeAll cannot stop"):
    given Logger[IO] = delayingLogger
    run:
      for
        (log, send) <- recorder
        subs        <- Subscriptions[IO](send)
        f           <- subs.add("1", Stream.awakeEvery[IO](25.milliseconds).as(ok)).start
        _           <- IO.sleep(100.milliseconds)
        _           <- f.cancel
        _           <- subs.removeAll
        obt         <- log.get
      yield assertEquals(obt.count(_ === "complete:1"), 1)

  test("a stream that ends naturally produces one Complete after the results"):
    run:
      for
        (log, send) <- recorder
        subs        <- Subscriptions[IO](send)
        _           <- subs.add("1", Stream(ok, ok).covary[IO])
        _           <- settle
        obt         <- log.get
      yield assertEquals(obt, List("next:1", "next:1", "complete:1"))

  test("a stream that fails sends an Error after the results, and no Complete"):
    run:
      for
        (log, send) <- recorder
        subs        <- Subscriptions[IO](send)
        _           <- subs.add("1", Stream(ok, ok).covary[IO] ++ Stream.raiseError[IO](boom))
        _           <- settle
        obt         <- log.get
      yield assertEquals(obt, List("next:1", "next:1", "error:1"))

  test("a stream that fails before the map entry exists still produces an Error"):
    given Logger[IO] = delayingLogger
    run:
      for
        (log, send) <- recorder
        subs        <- Subscriptions[IO](send)
        _           <- subs.add("1", Stream.raiseError[IO](boom))
        _           <- settle
        // A leaked entry would make one of these send a Complete for a dead subscription.
        _           <- subs.remove("1")
        _           <- subs.removeAll
        _           <- settle
        obt         <- log.get
      yield assertEquals(obt, List("error:1"))

  test("no Complete follows an Error, because Error ends the operation"):
    // `replySink` turns a Failure result into an Error message and the stream continues. The
    // protocol makes Error terminal for an id, so the finalizer must not add a Complete.
    run:
      for
        (log, send) <- recorder
        subs        <- Subscriptions[IO](send)
        _           <- subs.add("1", Stream(Result.failure[Json]("boom"), ok).covary[IO])
        _           <- settle
        obt         <- log.get
      yield assertEquals(obt, List("error:1", "next:1"))

  test("remove on a running subscription produces exactly one Complete"):
    run:
      for
        (log, send) <- recorder
        subs        <- Subscriptions[IO](send)
        _           <- subs.add("1", Stream.awakeEvery[IO](25.milliseconds).as(ok))
        _           <- IO.sleep(100.milliseconds)
        _           <- subs.remove("1")
        _           <- settle
        obt         <- log.get
      yield
        assert(obt.count(_ === "next:1") >= 1, "the stream sent nothing before remove")
        assertEquals(obt.count(_ === "complete:1"), 1)

  test("the finalizer of a replaced subscription does not remove the live entry"):
    // A duplicate id replaces the map entry. When the replaced stream ends, its finalizer must
    // leave the new entry alone, so that removeAll can still cancel the new fiber.
    run:
      for
        (log, send) <- recorder
        subs        <- Subscriptions[IO](send)
        ticks       <- Ref[IO].of(0)
        release     <- Deferred[IO, Unit]
        // Ends only when the test completes `release`.
        _           <- subs.add("1", Stream.eval(release.get).drain)
        // Replaces the entry for id "1" in the map.
        _           <- subs.add("1", Stream.awakeEvery[IO](25.milliseconds).evalTap(_ => ticks.update(_ + 1)).as(ok))
        _           <- IO.sleep(100.milliseconds)
        _           <- release.complete(())
        _           <- IO.sleep(100.milliseconds)
        _           <- subs.removeAll
        _           <- IO.sleep(100.milliseconds)
        before      <- ticks.get
        _           <- settle
        after       <- ticks.get
        obt         <- log.get
      yield
        assert(before >= 1, "the live stream sent nothing before removeAll")
        assertEquals(after, before, "the fiber of the live subscription outlived removeAll")
        assertEquals(obt.count(_ === "complete:1"), 1)
