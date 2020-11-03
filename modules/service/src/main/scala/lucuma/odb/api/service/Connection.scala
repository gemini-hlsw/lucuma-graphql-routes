// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.service

import cats.effect.concurrent.Ref
import cats.{Applicative, MonadError}
import lucuma.odb.api.service.ErrorFormatter.syntax._
import clue.model.StreamingMessage._
import clue.model.StreamingMessage.FromClient._
import clue.model.StreamingMessage.FromServer._
import cats.implicits._
import cats.effect.ConcurrentEffect
import clue.model.GraphQLRequest
import fs2.concurrent.NoneTerminatedQueue
import org.log4s.getLogger
import sangria.parser.QueryParser

/**
 * A web-socket connection that receives messages from a client and processes
 * them.
 */
sealed trait Connection[F[_]] {

  def receive(m: FromClient): F[Unit]

}

object Connection {

  import syntax.json._

  private[this] val logger = getLogger

  sealed trait ConnectionState[F[_]] {
    def init:                                   (ConnectionState[F], F[Unit])
    def start(id: String, req: GraphQLRequest): (ConnectionState[F], F[Unit])
    def stop(id: String):                       (ConnectionState[F], F[Unit])
    def terminate:                              (ConnectionState[F], F[Unit])
  }

  final class Connected[F[_]](
    odbService:    OdbService[F],
    replyQueue:    NoneTerminatedQueue[F, FromServer],
    subscriptions: Subscriptions[F]
  )(implicit F: ConcurrentEffect[F]) extends ConnectionState[F] {

    def info(m: String): F[Unit] =
      F.delay(logger.info(m))

    def reply(m: FromServer): F[Unit] =
      for {
        b <- replyQueue.offer1(Some(m))
        _ <- info(s"Connection.reply message $m ${if (b) "enqueued" else "DROPPED!"}")
      } yield ()

    override val init: (ConnectionState[F], F[Unit]) =
      (this, reply(ConnectionAck) *> reply(ConnectionKeepAlive))

    override def start(id: String, raw: GraphQLRequest): (ConnectionState[F], F[Unit]) = {
      val parseResult =
        QueryParser
          .parse(raw.query)
          .toEither
          .map(ParsedGraphQLRequest(_, raw.operationName, raw.variables))

      val action = parseResult match {
        case Left(err)                        => reply(Error(id, err.format))
        case Right(req) if req.isSubscription => subscribe(id, req)
        case Right(req)                       => execute(id, req)
      }

      (this, action)
    }

    override def stop(id: String): (ConnectionState[F], F[Unit]) =
      (this, subscriptions.remove(id))

    override val terminate: (ConnectionState[F], F[Unit]) =
      (new Terminated, subscriptions.terminate *> replyQueue.enqueue1(None))

    def subscribe(id: String, request: ParsedGraphQLRequest): F[Unit] =
      for {
        s <- odbService.subscribe(request)
        _ <- subscriptions.add(id, s)
      } yield ()

    def execute(id: String, request: ParsedGraphQLRequest): F[Unit] =
      for {
        r <- odbService.query(request)
        _ <- r.fold(
               err  => reply(Error(id, err.format)),
               json => reply(json.toStreamingMessage(id)) *> reply(Complete(id))
             )
      } yield ()

  }

  final class Terminated[F[_]](implicit F: Applicative[F], M: MonadError[F, Throwable]) extends ConnectionState[F] {

    private val raiseError: (ConnectionState[F], F[Unit]) =
      (this, M.raiseError(new RuntimeException("Connection was terminated")))

    override val init: (ConnectionState[F], F[Unit]) =
      raiseError

    override def start(id: String, req: GraphQLRequest): (ConnectionState[F], F[Unit]) =
      raiseError

    override def stop(id: String): (ConnectionState[F], F[Unit]) =
      raiseError

    override val terminate: (ConnectionState[F], F[Unit]) =
      (this, F.unit)
  }

  def apply[F[_]](
    odbService: OdbService[F],
    replyQueue: NoneTerminatedQueue[F, FromServer]
  )(
    implicit F: ConcurrentEffect[F]
  ): F[Connection[F]] =

    for {
      s <- Subscriptions(replyQueue)
      r <- Ref.of(new Connected[F](odbService, replyQueue, s): ConnectionState[F])
    } yield
      new Connection[F] {

        def handle(f: ConnectionState[F] => (ConnectionState[F], F[Unit])): F[Unit] =
          r.modify(f).flatten

        override def receive(m: FromClient): F[Unit] =
          m match {
            case ConnectionInit(_)   => handle(_.init)
            case Start(id, request)  => handle(_.start(id, request))
            case Stop(id)            => handle(_.stop(id))
            case ConnectionTerminate => handle(_.terminate)
          }
      }

}
