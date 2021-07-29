// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.service

import lucuma.core.model.User
import lucuma.sso.client.SsoClient
import cats.MonadError
import cats.effect.{Async, Ref}
import cats.effect.std.Queue
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import clue.model.GraphQLRequest
import clue.model.StreamingMessage._
import clue.model.StreamingMessage.FromClient._
import clue.model.StreamingMessage.FromServer._
import io.circe.Json
import org.typelevel.log4cats.Logger
import org.http4s.headers.Authorization

/**
 * A web-socket connection that receives messages from a client and processes
 * them.
 */
sealed trait Connection[F[_]] {

  /**
   * User associated with the connection, if known.
   */
  def user: F[Option[User]]

  /**
   * Accept a message from the client and process it, possibly changing state
   * and sending messages in return.
   *
   * @param m client provided message
   */
  def receive(m: FromClient): F[Unit]

  /**
   * Close the connection, never to be heard from again.
   */
  def close: F[Unit]

}

object Connection {

  import syntax.json._

  /**
   * Connection is a state machine that (typically) transitions from states
   * `PendingInit` to `Connected` to `Terminated` as it receives messages from
   * the client.
   */
  sealed trait ConnectionState[F[_]] {

    /**
     * The user associated with the connection, if known.  This should be
     * provided in the `connection_init` payload under the `Authorization` key.
     */
    def user: Option[User]

    /**
     * Initialize the connection with the given user, send reply function, and
     * subscriptions.
     *
     * @param user user associated with the connection, if known
     * @param send function to call in order to send a reply to the client
     * @param subs subscriptions being managed for this connection
     *
     * @return state transition and action to execute
     */
    def reset(
      user: Option[User],
      send: Option[FromServer] => F[Unit],
      subs: Subscriptions[F]
    ): (ConnectionState[F], F[Unit])

    /**
     * Starts a Graph QL operation associated with a particular id.
     *
     * @return state transition and action to execute
     */
    def start(id:  String, req: GraphQLRequest): (ConnectionState[F], F[Unit])

    /**
     * Terminates a Graph QL subscription associated with a particular id
     *
     * @return state transition and action to execute
     */
    def stop(id: String): (ConnectionState[F], F[Unit])

    /**
     * Stops all the subscriptions.
     *
     * @return state transition and action to execute
     */
    def stopAll: (ConnectionState[F], F[Unit])

    /**
     * Closes the connection, signaling that nothing more will be sent via
     * the reply queue.
     *
     * @return state transition and action
     */
    def close: (ConnectionState[F], F[Unit])

  }

  /**
   * PendingInit state.  Initial state, awaiting `connection_init` message that
   * contains the user authorization header.  Once it receives it, we transition
   * to `Connected`.
   */
  def pendingInit[F[_]: Logger](
    odbService: GraphQLService[F],
    replyQueue: Queue[F, Option[FromServer]]
  )(implicit ev: MonadError[F, Throwable]): ConnectionState[F] =

    new ConnectionState[F] {
      override def user: Option[User] =
        None

      override def reset(
        user: Option[User],
        send: Option[FromServer] => F[Unit],
        subs: Subscriptions[F]
      ): (ConnectionState[F], F[Unit]) =
        (connected(odbService, user, send, subs),
         send(Some(ConnectionAck)) *> send(Some(ConnectionKeepAlive))
        )

      private def doClose(m: String): (ConnectionState[F], F[Unit]) =
        (closed(None),
         for {
           _ <- info(None, s"Request received on un-initialized connection: $m. Closing.")
           _ <- replyQueue.offer(None)
         } yield ()
        )

      override def start(id: String, req: GraphQLRequest): (ConnectionState[F], F[Unit]) =
        doClose(s"start($id, $req)")

      override def stop(id: String): (ConnectionState[F], F[Unit]) =
        doClose(s"stop($id)")

      override val stopAll: (ConnectionState[F], F[Unit]) =
        doClose("stopAll")

      override val close: (ConnectionState[F], F[Unit]) =
        doClose("close")

    }

  /**
   * Connected state. Post-initialization, we stay in the `Connected` state
   * until explicitly terminated by the client.
   */
  def connected[F[_]: Logger](
    odbService:    GraphQLService[F],
    usr:           Option[User],
    send:          Option[FromServer] => F[Unit],
    subscriptions: Subscriptions[F]
  )(implicit ev: MonadError[F, Throwable]): ConnectionState[F] =

    new ConnectionState[F] {

      import odbService.ParsedGraphQLRequest

      override def reset(
        u: Option[User],
        r: Option[FromServer] => F[Unit],
        s: Subscriptions[F]
      ): (ConnectionState[F], F[Unit]) =
        (connected(odbService, u, r, s),
          subscriptions.removeAll  *>
            r(Some(ConnectionAck)) *>
            r(Some(ConnectionKeepAlive))
        )

      override def user: Option[User] =
        usr

      override def start(id: String, raw: GraphQLRequest): (ConnectionState[F], F[Unit]) = {

        val parseResult =
          odbService
            .parse(raw.query)
            .map(ParsedGraphQLRequest(_, raw.operationName, raw.variables))

        val action = parseResult match {
          case Left(err)                        => send(Some(Error(id, odbService.format(err))))
          case Right(req) if req.isSubscription => subscribe(id, req)
          case Right(req)                       => execute(id, req)
        }

        (this, action)

      }

      override def stop(id: String): (ConnectionState[F], F[Unit]) =
        (this, subscriptions.remove(id))

      override val stopAll: (ConnectionState[F], F[Unit]) =
        (this, subscriptions.removeAll)

      def subscribe(id: String, request: ParsedGraphQLRequest): F[Unit] =
        for {
          s <- odbService.subscribe(user, request)
          _ <- subscriptions.add(id, s)
        } yield ()

      def execute(id: String, request: ParsedGraphQLRequest): F[Unit] =
        for {
          r <- odbService.query(request)
          _ <- r.fold(
                 err  => send(Some(Error(id, odbService.format(err)))),
                 json => send(Some(json.toStreamingMessage(id))) *> send(Some(Complete(id)))
               )
        } yield ()

      override val close: (ConnectionState[F], F[Unit]) =
        (closed(user), subscriptions.removeAll *> send(None))
    }

  /**
   * Closed state.  All requests raise an error, the connection having been closed.
   */
  def closed[F[_]](
    usr: Option[User]
  )(implicit M: MonadError[F, Throwable]): ConnectionState[F] =

    new ConnectionState[F] {

      override def user: Option[User] =
        usr

      private val raiseError: (ConnectionState[F], F[Unit]) =
        (this, M.raiseError(new RuntimeException(s"Connection was terminated: (user=$user)")))

      override def reset(
        u: Option[User],
        r: Option[FromServer] => F[Unit],
        s: Subscriptions[F]
      ): (ConnectionState[F], F[Unit]) =
        raiseError

      override def start(id: String, req: GraphQLRequest): (ConnectionState[F], F[Unit]) =
        raiseError

      override def stop(id: String): (ConnectionState[F], F[Unit]) =
        raiseError

      override val stopAll: (ConnectionState[F], F[Unit]) =
        raiseError

      override val close: (ConnectionState[F], F[Unit]) =
        (this, M.unit)
    }


  def apply[F[_]: Logger](
    odbService: GraphQLService[F],
    userClient: SsoClient[F, User],
    replyQueue: Queue[F, Option[FromServer]]
  )(implicit F: Async[F]): F[Connection[F]] =

    Ref.of(pendingInit[F](odbService, replyQueue)).map { stateRef =>

      new Connection[F] {

        override def user: F[Option[User]] =
          stateRef.get.map(_.user)

        def handle(f: ConnectionState[F] => (ConnectionState[F], F[Unit])): F[Unit] =
          stateRef.modify(f).flatten

        def parseConnectionProps(
          connectionProps: Map[String, Json]
        ): F[Option[Authorization]] =
          connectionProps
            .get("Authorization")
            .flatMap(_.asString)
            .map(Authorization.parse)
            .flatTraverse {
              case Left(err) => F.raiseError[Option[Authorization]](new RuntimeException(err.message, err.cause.orNull))
              case Right(a)  => F.pure(Some(a))
            }

        /**
         * Connection initialization upon receipt of a `connection_init`
         * message.  These actions are done outside of any particular state
         * because they require executing effects in addition to the state
         * transition itself.  Namely: user lookup based on authentication
         * data in the `connection_init` payload and the creation of the
         * Subscriptions object with its `Ref` for tracking subscriptions.
         *
         * @param connectionProps properties extracted from the `connection_init`
         *                        payload
         */
        def init(connectionProps: Map[String, Json]): F[Unit] = {

          // Creates the function used to send replies to the client.  It
          // just offers a message to the reply queue and logs it.
          def reply(user: Option[User]): Option[FromServer] => F[Unit] = { m =>
            for {
              b <- replyQueue.tryOffer(m)
              _ <- info(user, s"Subscriptions send $m ${if (b) "enqueued" else "DROPPED!"}")
            } yield ()
          }

          for {
            a <- parseConnectionProps(connectionProps)
            u <- a.fold(F.pure(Option.empty[User]))(userClient.get)
            r  = reply(u)
            s <- Subscriptions(odbService, u, r)
            _ <- handle(_.reset(u, r, s))
          } yield ()

        }

        override def receive(m: FromClient): F[Unit] =
          m match {
            case ConnectionInit(m)   => init(m)
            case Start(id, request)  => handle(_.start(id, request))
            case Stop(id)            => handle(_.stop(id))
            case ConnectionTerminate => handle(_.stopAll)
          }

        override def close: F[Unit] =
          handle(_.close)
      }
    }
}
