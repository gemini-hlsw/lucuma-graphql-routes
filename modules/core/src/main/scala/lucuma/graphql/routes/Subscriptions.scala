// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.Monad
import cats.effect.Concurrent
import cats.effect.Fiber
import cats.effect.Ref
import cats.effect.syntax.all._
import cats.implicits._
import clue.model.StreamingMessage.FromServer._
import clue.model.StreamingMessage._
import fs2.Pipe
import fs2.Stream
import fs2.concurrent.SignallingRef
import io.circe.Json
import org.typelevel.log4cats.Logger


/** A GraphQL subscription in effect type F. */
trait Subscriptions[F[_]] {

  /**
   * Adds a new subscription receiving events from the provided `Stream`.
   * @param id     client-provided id for the subscription
   * @param events stream of Either errors or Json results that match the subscription query
   */
  def add(id: String, events: Stream[F, Either[Throwable, Json]]): F[Unit]

  /**
   * Removes a subscription so that it no longer provides events to the client.
   * @param id client-provided id
   */
  def remove(id: String): F[Unit]

  /** Removes all subscriptions. */
  def removeAll: F[Unit]

}

object Subscriptions {

  import syntax.json._

  /**
   * Tracks a single client subscription.
   * @param id      Client-supplied identification for the subscription.
   * @param results Underlying stream of results, each of which is an Either error or subscription
   *  query match
   */
  private final class Subscription[F[_]: Monad](
    val id:      String,
    val results: Fiber[F, Throwable, Unit],
    val stopped: SignallingRef[F, Boolean]
  ) {

    val isStopped: F[Boolean] =
      stopped.get

    val stop: F[Unit] =
      for {
        _ <- stopped.set(true)
        _ <- results.cancel
      } yield ()

  }

  // Converts raw graphQL subscription events into FromServer messages.
  private def fromServerPipe[F[_]](id: String, service: GraphQLService[F]): Pipe[F, Either[Throwable, Json], FromServer] =
    _.flatMap {
      case Left(err)   => Stream.eval(service.format(err)).map(Error(id, _))
      case Right(json) => Stream(json.toStreamingMessage(id))
    }

  def apply[F[_]: Logger: Concurrent](
    service: GraphQLService[F],
    send: Option[FromServer] => F[Unit]
  ): F[Subscriptions[F]] =

    Ref[F].of(Map.empty[String, Subscription[F]]).map { subscriptions =>
      new Subscriptions[F]() {

        def replySink(id: String): Pipe[F, Either[Throwable, Json], Unit] =
          events => fromServerPipe(id, service)(events).evalMap(m => send(Some(m)))

        override def add(id: String, events: Stream[F, Either[Throwable, Json]]): F[Unit] =
          for {
            r <- SignallingRef(false)
            in = r.discrete.evalTap(v => info(s"signalling ref = $v"))
            es = events.through(replySink(id)).interruptWhen(in)
            _ <- debug(s"starting event stream $id")
            f <- es.compile.drain.start
            _ <- debug(s"started event stream $id")
            _ <- subscriptions.update(_.updated(id, new Subscription(id, f, r)))
          } yield ()

        override def remove(id: String): F[Unit] =
          for {
            m <- subscriptions.getAndUpdate(_.removed(id))
            _ <- m.get(id).fold(().pure[F])(s => s.stop *> send(Some(Complete(s.id))))
          } yield ()

        override def removeAll: F[Unit] =
          for {
            m <- subscriptions.getAndSet(Map.empty[String, Subscription[F]])
            _ <- m.values.toList.traverse_(s => s.stop *> send(Some(Complete(s.id))))
          } yield ()

      }
    }
}
