// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadError
import cats.effect.Concurrent
import cats.effect.Ref
import cats.effect.std.Queue
import cats.syntax.all._
import clue.model.GraphQLRequest
import clue.model.StreamingMessage.FromClient._
import clue.model.StreamingMessage.FromServer._
import clue.model.StreamingMessage._
import io.circe.Json
import org.http4s.headers.Authorization
import org.typelevel.log4cats.Logger
import org.http4s.ParseResult

/** A web-socket connection that receives messages from a client and processes them. */
sealed trait Connection[F[_]] {

  /**
   * Accept a message from the client and process it, possibly changing state and sending messages
   * in return.
   * @param m client provided message
   */
  def receive(m: FromClient): F[Unit]

  /** Close the connection, never to be heard from again. */
  def close: F[Unit]

}

object Connection {

  import syntax.json._

  /**
   * Connection is a state machine that (typically) transitions from states `PendingInit` to
   * `Connected` to `Terminated` as it receives messages from the client.
   */
  sealed trait ConnectionState[F[_]] {

    /**
     * Initialize the connection with the given send reply function and subscriptions.
     * @param send function to call in order to send a reply to the client
     * @param subs subscriptions being managed for this connection
     * @return state transition and action to execute
     */
    def reset(
      service: GraphQLService[F],
      send: Option[FromServer] => F[Unit],
      subs: Subscriptions[F]
    ): (ConnectionState[F], F[Unit])

    /**
     * Starts a Graph QL operation associated with a particular id.
     * @return state transition and action to execute
     */
    def start(id:  String, req: GraphQLRequest): (ConnectionState[F], F[Unit])

    /**
     * Terminates a Graph QL subscription associated with a particular id
     * @return state transition and action to execute
     */
    def stop(id: String): (ConnectionState[F], F[Unit])

    /**
     * Stops all the subscriptions.
     * @return state transition and action to execute
     */
    def stopAll: (ConnectionState[F], F[Unit])

    /**
     * Closes the connection, signaling that nothing more will be sent via the reply queue.
     * @return state transition and action
     */
    def close: (ConnectionState[F], F[Unit])

  }

  /**
   * PendingInit state. Initial state, awaiting `connection_init` message that contains the user
   * authorization header. Once it receives it, we transition to `Connected`.
   */
  def pendingInit[F[_]: Logger](
    replyQueue: Queue[F, Option[FromServer]]
  )(implicit ev: MonadError[F, Throwable]): ConnectionState[F] =

    new ConnectionState[F] {

      override def reset(
        service: GraphQLService[F],
        send: Option[FromServer] => F[Unit],
        subs: Subscriptions[F],
      ): (ConnectionState[F], F[Unit]) =
        (connected(service, send, subs),
         send(Some(ConnectionAck)) *> send(Some(ConnectionKeepAlive))
        )

      private def doClose(m: String): (ConnectionState[F], F[Unit]) =
        (closed,
         for {
           _ <- info(s"Request received on un-initialized connection: $m. Closing.")
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
   * Connected state. Post-initialization, we stay in the `Connected` state until explicitly
   * terminated by the client.
   */
  def connected[F[_]: Logger](
    service:    GraphQLService[F],
    send:          Option[FromServer] => F[Unit],
    subscriptions: Subscriptions[F]
  )(implicit ev: MonadError[F, Throwable]): ConnectionState[F] =

    new ConnectionState[F] {

      import service.ParsedGraphQLRequest

      override def reset(
        service: GraphQLService[F],
        r: Option[FromServer] => F[Unit],
        s: Subscriptions[F]
      ): (ConnectionState[F], F[Unit]) =
        (connected(service, r, s),
          subscriptions.removeAll  *>
            r(Some(ConnectionAck)) *>
            r(Some(ConnectionKeepAlive))
        )

      override def start(id: String, raw: GraphQLRequest): (ConnectionState[F], F[Unit]) = {

        val parseResult =
          service
            .parse(raw.query)
            .map(ParsedGraphQLRequest(_, raw.operationName, raw.variables))

        val action = parseResult match {
          case Left(err)  => service.format(err).flatMap { json => send(Some(Error(id, json))) }
          case Right(req) => if (service.isSubscription(req)) subscribe(id, req) else execute(id, req)
        }

        (this, action)

      }

      override def stop(id: String): (ConnectionState[F], F[Unit]) =
        (this, subscriptions.remove(id))

      override val stopAll: (ConnectionState[F], F[Unit]) =
        (this, subscriptions.removeAll)

      def subscribe(id: String, request: ParsedGraphQLRequest): F[Unit] =
        subscriptions.add(id, service.subscribe(request))

      def execute(id: String, request: ParsedGraphQLRequest): F[Unit] =
        for {
          r <- service.query(request)
          _ <- r.fold(
                 err  => service.format(err).flatMap(json => send(Some(Error(id, json)))),
                 json => send(Some(json.toStreamingMessage(id))) *> send(Some(Complete(id)))
               )
        } yield ()

      override val close: (ConnectionState[F], F[Unit]) =
        (closed, subscriptions.removeAll *> send(None))
    }

  /** Closed state.  All requests raise an error, the connection having been closed. */
  def closed[F[_]](implicit M: MonadError[F, Throwable]): ConnectionState[F] =

    new ConnectionState[F] {

      private val raiseError: (ConnectionState[F], F[Unit]) =
        (this, M.raiseError(new RuntimeException("Connection was terminated.")))

      override def reset(
        service: GraphQLService[F],
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
    service: Option[Authorization] => F[Option[GraphQLService[F]]],
    replyQueue: Queue[F, Option[FromServer]]
  )(implicit F: Concurrent[F]): F[Connection[F]] =

    Ref.of(pendingInit[F](replyQueue)).map { stateRef =>

      new Connection[F] {

        def handle(f: ConnectionState[F] => (ConnectionState[F], F[Unit])): F[Unit] =
          stateRef.modify(f).flatten

        def parseAuthorization(
          connectionProps: Map[String, Json]
        ): Option[ParseResult[Authorization]] =
          connectionProps
            .get("Authorization")
            .flatMap(_.asString)
            .map(Authorization.parse)

        /**
         * Connection initialization upon receipt of a `connection_init` message.  These actions are
         * done outside of any particular state because they require executing effects in addition
         * to the state transition itself.  Namely: user lookup based on authentication data in the
         * `connection_init` payload and the creation of the Subscriptions object with its `Ref` for
         * tracking subscriptions.
         * @param connectionProps properties extracted from the `connection_init` payload
         */
        def init(connectionProps: Map[String, Json]): F[Unit] = {

          // Creates the function used to send replies to the client. It just offers a message to
          // the reply queue and logs it.
          val reply: Option[FromServer] => F[Unit] = { m =>
            for {
              b <- replyQueue.tryOffer(m)
              _ <- info(s"Subscriptions send $m ${if (b) "enqueued" else "DROPPED!"}")
            } yield ()
          }

          // Given an optional Authorization, get a service and start a subscription (if allowed)
          def trySubscribe(opAuth: Option[Authorization]): F[Unit] =
            service(opAuth).flatMap {

              // User is authorized. Go.
              case Some(svc) =>
                Subscriptions(svc, reply).flatMap(s => handle(_.reset(svc, reply, s)))

              // User has insufficient privileges to connect.
              case None =>
                reply(Some(FromServer.Error("<none>", Json.fromString("Not authorized.")))) *>
                handle(_.close)

            }

          // Either subscribe or error out, based on the Authorization property (if any)
          parseAuthorization(connectionProps) match {

            // Authorization header is present and well-formed
            case Some(Right(auth)) =>
              trySubscribe(Some(auth))

            // Authorization header is missing
            case None =>
              trySubscribe(None)

            // Authorization header is present but malformed.
            case Some(Left(_)) =>
              reply(Some(FromServer.Error("<none>", Json.fromString(s"Authorization property is malformed.")))) *>
              handle(_.close)

          }

        }

        override def receive(m: FromClient): F[Unit] =
          Logger[F].info(s"received $m") *> {
            m match {
              case ConnectionInit(m)   => init(m)
              case Start(id, request)  => handle(_.start(id, request))
              case Stop(id)            => handle(_.stop(id))
              case ConnectionTerminate => handle(_.stopAll)
            }
          }

        override def close: F[Unit] =
          handle(_.close)
      }
    }
}
