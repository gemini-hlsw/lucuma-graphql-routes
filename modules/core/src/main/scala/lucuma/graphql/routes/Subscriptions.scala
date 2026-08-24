// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.Monad
import cats.effect.Concurrent
import cats.effect.Deferred
import cats.effect.Fiber
import cats.effect.Outcome
import cats.effect.Ref
import cats.effect.syntax.all.*
import cats.implicits.*
import clue.model.StreamingMessage.*
import clue.model.StreamingMessage.FromServer.*
import fs2.Pipe
import fs2.Stream
import fs2.concurrent.SignallingRef
import grackle.Result
import io.circe.Json
import org.typelevel.log4cats.Logger

/** A GraphQL subscription in effect type F. */
trait Subscriptions[F[_]] {

  /**
   * Adds a new subscription receiving events from the provided `Stream`.
   * @param id     client-provided id for the subscription
   * @param events stream of Either errors or Json results that match the subscription query
   */
  def add(id: String, events: Stream[F, Result[Json]]): F[Unit]

  /**
   * Removes a subscription so that it no longer provides events to the client.
   * @param id client-provided id
   */
  def remove(id: String): F[Unit]

  /** Removes all subscriptions. */
  def removeAll: F[Unit]

}

object Subscriptions {

  /**
   * Tracks a single client subscription.
   * @param results Underlying stream of results, each of which is an Either error or subscription
   *  query match
   * @param stopped Set to true to interrupt the stream. Also identifies the subscription.
   */
  private final class Subscription[F[_]: Monad](
    val results: Deferred[F, Fiber[F, Throwable, Unit]],
    val stopped: SignallingRef[F, Boolean]
  ) {

    val stop: F[Unit] =
      for {
        _ <- stopped.set(true)
        f <- results.get
        _ <- f.cancel
      } yield ()

  }

  def apply[F[_]: Logger: Concurrent](
    send: Option[FromServer] => F[Unit]
  ): F[Subscriptions[F]] =

    Ref[F].of(Map.empty[String, Subscription[F]]).map { subscriptions =>
      new Subscriptions[F]() {

        // The caller that takes an entry out of the map owns the `Complete` for that id.
        def stopAndComplete(id: String, s: Subscription[F]): F[Unit] =
          s.stop *> send(Some(Complete(id)))

        // `errorSent` records that an `error` message went to the client for this id. The
        // protocol makes `error` a terminal message, so no `complete` can follow it.
        def replySink(id: String, errorSent: Ref[F, Boolean]): Pipe[F, Result[Json], Unit] =
          _.evalMap { r =>
            for {
              e <- mkFromServer(r, id)
              _ <- errorSent.set(true).whenA(e.isLeft)
              _ <- send(Some(e.merge))
            } yield ()
          }

        override def add(id: String, events: Stream[F, Result[Json]]): F[Unit] =
          (for {
            r         <- SignallingRef(false)
            errorSent <- Ref[F].of(false)
            in         = r.discrete.evalTap(v => Logger[F].debug(s"signalling ref = $v"))
            removeOwn  = subscriptions.modify: m =>
                            if (m.get(id).exists(_.stopped eq r)) (m.removed(id), true)
                            else (m, false)
            complete   = for
                           own <- removeOwn
                           err <- errorSent.get
                           _   <- send(Complete(id).some).whenA(own && !err)
                         yield ()
            // A failure of the source stream is reported to the client as a terminal `error`
            // message. No `complete` follows it.
            error      = (t: Throwable) =>
                           for
                             own <- removeOwn
                             err <- errorSent.get
                             _   <- send(Error(id, mkGraphqlErrors(t)).some).whenA(own && !err)
                           yield ()
            es         = events.through(replySink(id, errorSent)).interruptWhen(in)
            fiber     <- Deferred[F, Fiber[F, Throwable, Unit]]
            _         <- subscriptions.update(_.updated(id, new Subscription(fiber, r)))
            _         <- Logger[F].debug(s"starting event stream $id")
            f         <- es.compile.drain
                           .guaranteeCase:
                             case Outcome.Succeeded(_) => complete
                             case Outcome.Errored(t)   => error(t)
                             case Outcome.Canceled()   => removeOwn.void
                           .start
            _         <- fiber.complete(f)
            _         <- Logger[F].debug(s"started event stream $id")
          } yield ()).uncancelable // cancellation before fiber.complete would block every stop

        override def remove(id: String): F[Unit] =
          subscriptions
            .getAndUpdate(_.removed(id))
            .flatMap(_.get(id).traverse_(stopAndComplete(id, _)))

        override def removeAll: F[Unit] =
          subscriptions
            .getAndSet(Map.empty[String, Subscription[F]])
            .flatMap(_.toList.traverse_((id, s) => stopAndComplete(id, s)))

      }
    }
}
