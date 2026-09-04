// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.FlatMap
import cats.effect.Concurrent
import cats.effect.Deferred
import cats.effect.Fiber
import cats.effect.Ref
import cats.effect.Resource.ExitCase
import cats.effect.implicits.*
import cats.effect.std.Supervisor
import cats.implicits.*
import clue.model.StreamingMessage.*
import clue.model.StreamingMessage.FromServer.*
import fs2.Pipe
import fs2.Stream
import grackle.Result
import io.circe.Json
import org.typelevel.log4cats.Logger

/** The active GraphQL operations of a connection, in effect type F. */
trait Subscriptions[F[_]] {

  /**
   * Adds a new operation receiving events from the provided `Stream`. A query or mutation is a
   * one-element stream.
   * @param id
   *   client-provided id for the operation
   * @param events
   *   stream of Either errors or Json results that the operation produces
   * @return
   *   true if the event stream started, false if the id is already in use. The caller decides what
   *   a duplicate id means for the connection.
   */
  def add(id: String, events: Stream[F, Result[Json]]): F[Boolean]

  /**
   * Removes an operation so that it no longer provides events to the client.
   * @param id
   *   client-provided id
   */
  def remove(id: String): F[Unit]

  /** Removes all operations. */
  def removeAll: F[Unit]

}

object Subscriptions {

  /**
   * Tracks a single client operation.
   * @param fiber
   *   Holds the fiber of the event stream once the map entry for the operation exists. The event
   *   stream waits for it, so the stream cannot end before the entry that its finalizer must clean
   *   up exists.
   * @param errorSent
   *   True once an `error` message went to the client for this id.
   */
  private final class Subscription[F[_]: FlatMap](
    val fiber:     Deferred[F, Fiber[F, Throwable, Unit]],
    val errorSent: Ref[F, Boolean]
  ) {
    val stop: F[Unit] = fiber.get.flatMap(_.cancel)
  }

  def apply[F[_]: {Concurrent, Logger as L}](
    supervisor: Supervisor[F],
    send:       FromServer => F[Unit]
  ): F[Subscriptions[F]] =

    Ref[F].of(Map.empty[String, Subscription[F]]).map { subscriptions =>
      new Subscriptions[F]() {

        /**
         * Cancels the subscription without sending a terminal message to the client.
         */
        private def stopOnly(id: String, s: Subscription[F]): F[Unit] =
          s.stop.handleErrorWith(t => L.warn(t)(s"could not remove operation $id"))

        /**
         * Cancels the subscription and sends a terminal message to the client, unless an `error`
         * message already went to the client.
         */
        private def stopAndComplete(id: String, s: Subscription[F]): F[Unit] =
          (s.stop *> s.errorSent.get.flatMap(err => send(Complete(id)).unlessA(err)))
            .handleErrorWith(t => L.warn(t)(s"could not remove operation $id"))

        /**
         * Inserts a new operation and starts its event stream. If the id is already in use, the
         * stream does not start and the map does not change.
         */
        private def insertAndStart(
          id:    String,
          entry: Subscription[F],
          run:   F[Unit]
        ): F[Boolean] =
          subscriptions.flatModify: m =>
            if (m.contains(id))
              (
                m,
                L.debug(s"duplicate operation id $id").as(false)
              )
            else
              (m.updated(id, entry),
               for
                 _     <- L.debug(s"starting event stream $id")
                 fiber <- supervisor.supervise(run)
                 _     <- entry.fiber.complete(fiber)
                 _     <- L.debug(s"started event stream $id")
               yield true
              )

        // `errorSent` records that an `error` message went to the client for this id. The
        // protocol makes `error` a terminal message, so the stream ends after the first `error`
        // and no `complete` can follow it. The send, the flag and the removal from the map form
        // one uncancelable step. The client can reuse the id as soon as it has the `error`
        // message, so the entry must not outlive that message.
        private def replySink(
          id:        String,
          errorSent: Ref[F, Boolean],
          removeOwn: F[Boolean]
        ): Pipe[F, Result[Json], Unit] =
          _.evalMap(mkFromServer(_, id))
            .takeThrough(_.isRight)
            .evalMap {
              case Left(e)  => (send(e) *> errorSent.set(true) *> removeOwn.void).uncancelable
              case Right(n) => send(n)
            }

        override def add(id: String, events: Stream[F, Result[Json]]): F[Boolean] =
          (for {
            errorSent   <- Ref[F].of(false)
            fiberD      <- Deferred[F, Fiber[F, Throwable, Unit]]
            entry        = new Subscription(fiberD, errorSent)
            removeOwn    = subscriptions.modify: m =>
                             if (m.get(id).exists(_ eq entry)) (m.removed(id), true)
                             else (m, false)
            // The terminal message for this id, unless another caller took the entry out of
            // the map or a terminal `error` message already went to the client.
            sendLast     = (m: FromServer) =>
                             for
                               own <- removeOwn
                               err <- errorSent.get
                               _   <- send(m).whenA(own && !err)
                             yield ()
            // The stream waits for its own fiber, so it cannot end before its map entry
            // exists. A natural end sends `complete` and a failure sends a terminal `error`.
            // Cancellation sends nothing, because the canceller decides what the client gets.
            subscription = (Stream.exec(fiberD.get.void) ++
                             events.through(replySink(id, errorSent, removeOwn)))
                             .onFinalizeCase {
                               case ExitCase.Succeeded  => sendLast(Complete(id))
                               case ExitCase.Errored(t) => sendLast(Error(id, mkGraphqlErrors(t)))
                               case ExitCase.Canceled   => removeOwn.void
                             }
                             .compile
                             .drain
            result      <- insertAndStart(id, entry, subscription)
          } yield result).uncancelable

        // A client `complete` message ends the operation. The client already stopped listening,
        // so the server must not send a `complete` back for that id. The client can reuse the id
        // as soon as it sent the message, and a late `complete` would end the operation that
        // reuses it.
        override def remove(id: String): F[Unit] =
          subscriptions.flatModifyFull: (poll, s) =>
            (s.removed(id), s.get(id).traverse_(sub => poll(stopOnly(id, sub))))

        override def removeAll: F[Unit] =
          subscriptions.flatModifyFull: (poll, s) =>
            (Map.empty, poll(s.toList.parTraverse_(stopAndComplete(_, _))))

      }
    }
}
