// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadError
import cats.MonadThrow
import cats.data.NonEmptyList
import cats.effect.Concurrent
import cats.effect.Ref
import cats.effect.std.Queue
import cats.syntax.all.*
import clue.model.GraphQLError
import clue.model.GraphQLRequest
import clue.model.StreamingMessage.*
import clue.model.StreamingMessage.FromClient.*
import clue.model.StreamingMessage.FromServer.*
import clue.model.json.given
import grackle.Operation
import grackle.Result.*
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*
import lucuma.graphql.routes.mkGraphqlError
import natchez.Trace
import org.http4s.ParseResult
import org.http4s.headers.Authorization
import org.typelevel.log4cats.Logger

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
    def start(id:  String, req: GraphQLRequest[JsonObject]): (ConnectionState[F], F[Unit])

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
  def pendingInit[F[_]: Logger: Trace](
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
           _ <- Logger[F].debug(s"Request received on un-initialized connection: $m. Closing.")
           _ <- replyQueue.offer(None)
         } yield ()
        )

      override def start(id: String, req: GraphQLRequest[JsonObject]): (ConnectionState[F], F[Unit]) =
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
  def connected[F[_]: Logger: MonadThrow: Trace](
    service:       GraphQLService[F],
    send:          Option[FromServer] => F[Unit],
    subscriptions: Subscriptions[F]
  ): ConnectionState[F] =

    new ConnectionState[F] {

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

      override def start(id: String, raw: GraphQLRequest[JsonObject]): (ConnectionState[F], F[Unit]) = {
        val parseResult = service.parse(raw.query.value, raw.operationName, raw.variables)
        val action = parseResult match {
          case Success(op)        => if (service.isSubscription(op)) subscribe(id, op) else execute(id, op)
          case Warning(_, op)     => if (service.isSubscription(op)) subscribe(id, op) else execute(id, op) // n.b. warnings on subscribe are lost
          case Failure(ps)        => send(Error(id, ps.toNonEmptyList.map(mkGraphqlError)).some)
          case InternalError(err) => send(Error(id, mkGraphqlErrors(err)).some)
        }
        (this, action)
      }

      override def stop(id: String): (ConnectionState[F], F[Unit]) =
        (this, subscriptions.remove(id))

      override val stopAll: (ConnectionState[F], F[Unit]) =
        (this, subscriptions.removeAll)

      def subscribe(id: String, request: Operation): F[Unit] =
        Trace[F].span("connection.subscribe"):
          Trace[F].put("connection.fromclient.id" -> id) >>
          Trace[F].put(service.props*) >>
          subscriptions.add(id, service.subscribe(request))

      def execute(id: String, request: Operation): F[Unit] =
        Trace[F].span("connection.execute"):
          Trace[F].put("connection.fromclient.id" -> id) >>
          Trace[F].put(service.props*) >>
          service.query(request).flatMap { r =>
            mkFromServer(r, id).flatMap {
              case Right(data) => send(data.some) *> send(Complete(id).some)
              case Left(error) => send(error.some)
            }
          }

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

      override def start(id: String, req: GraphQLRequest[JsonObject]): (ConnectionState[F], F[Unit]) =
        raiseError

      override def stop(id: String): (ConnectionState[F], F[Unit]) =
        raiseError

      override val stopAll: (ConnectionState[F], F[Unit]) =
        raiseError

      override val close: (ConnectionState[F], F[Unit]) =
        (this, M.unit)
    }


  def apply[F[_]: Logger: Trace](
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
        def init(connectionProps: Map[String, Json]): F[Unit] = Trace[F].span("connection.init") {

          // Creates the function used to send replies to the client. It just offers a message to
          // the reply queue and logs it.
          val reply: Option[FromServer] => F[Unit] = { m =>
            for {
              b <- replyQueue.tryOffer(m)
              _ <- Logger[F].debug(s"Subscriptions send $m ${if (b) "enqueued" else "DROPPED!"}")
            } yield ()
          }

          // Given an optional Authorization, get a service and start a subscription (if allowed)
          def trySubscribe(opAuth: Option[Authorization]): F[Unit] =
            service(opAuth).flatMap {

              // User is authorized. Go.
              case Some(svc) =>
                Trace[F].put(svc.props*) >>
                Subscriptions(reply).flatMap(s => handle(_.reset(svc, reply, s)))

              // User has insufficient privileges to connect.
              case None =>
                reply(Some(FromServer.ConnectionError(GraphQLError("Not authorized.").asJsonObject))) *>
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
              reply(Some(FromServer.Error("<none>", NonEmptyList.one(GraphQLError("Authorization property is malformed."))))) *>
              handle(_.close)

          }

        }

        override def receive(m: FromClient): F[Unit] =
          Logger[F].debug(s"received $m") *> {
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
