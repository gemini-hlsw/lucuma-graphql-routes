// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadError
import cats.MonadThrow
import cats.effect.Concurrent
import cats.effect.Ref
import cats.effect.std.Queue
import cats.syntax.all.*
import clue.model.GraphQLRequest
import clue.model.StreamingMessage.*
import clue.model.StreamingMessage.FromClient.*
import clue.model.StreamingMessage.FromServer.*
import grackle.Operation
import grackle.Result.*
import io.circe.JsonObject
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
      send: Option[Either[GraphQLWSError, FromServer]] => F[Unit],
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
     * Closes the connection, signaling that nothing more will be sent via the reply queue.
     * @return state transition and action
     */
    def close(reason: Option[GraphQLWSError]): (ConnectionState[F], F[Unit])

  }

  /**
   * PendingInit state. Initial state, awaiting `connection_init` message that contains the user
   * authorization header. Once it receives it, we transition to `Connected`.
   */
  def pendingInit[F[_]: Logger: Trace](
    replyQueue: Queue[F, Option[Either[GraphQLWSError, FromServer]]]
  )(implicit ev: MonadError[F, Throwable]): ConnectionState[F] =

    new ConnectionState[F] {

      override def reset(
        service: GraphQLService[F],
        send: Option[Either[GraphQLWSError, FromServer]] => F[Unit],
        subs: Subscriptions[F],
      ): (ConnectionState[F], F[Unit]) =
        (connected(service, send, subs),
         send(ConnectionAck().asRight.some) *> send(Ping().asRight.some)
        )


      override def start(id: String, req: GraphQLRequest[JsonObject]): (ConnectionState[F], F[Unit]) =
        doClose(s"start($id, $req)")

      override def stop(id: String): (ConnectionState[F], F[Unit]) =
        doClose(s"stop($id)")

      private def doClose(m: String): (ConnectionState[F], F[Unit]) =
        close(GraphQLWSError.Unauthorized(m).some)

      override def close(reason: Option[GraphQLWSError]): (ConnectionState[F], F[Unit]) =
        (closed, replyQueue.offer(reason.map(_.asLeft)))

    }

  /**
   * Connected state. Post-initialization, we stay in the `Connected` state until explicitly
   * terminated by the client.
   */
  def connected[F[_]: Logger: MonadThrow: Trace](
    service:       GraphQLService[F],
    send:          Option[Either[GraphQLWSError, FromServer]] => F[Unit],
    subscriptions: Subscriptions[F]
  ): ConnectionState[F] =

    new ConnectionState[F] {

      override def reset(
        service: GraphQLService[F],
        r: Option[Either[GraphQLWSError, FromServer]] => F[Unit],
        s: Subscriptions[F]
      ): (ConnectionState[F], F[Unit]) =
        (connected(service, r, s),
          subscriptions.removeAll  *>
            r(ConnectionAck().asRight.some) *>
            r(Ping().asRight.some)
        )

      override def start(id: String, raw: GraphQLRequest[JsonObject]): (ConnectionState[F], F[Unit]) = {
        val parseResult = service.parse(raw.query.value, raw.operationName, raw.variables)
        val action = parseResult match {
          case Success(op)        => if (service.isSubscription(op)) subscribe(id, op) else execute(id, op)
          case Warning(_, op)     => if (service.isSubscription(op)) subscribe(id, op) else execute(id, op) // n.b. warnings on subscribe are lost
          case Failure(ps)        => send(Error(id, ps.toNonEmptyList.map(mkGraphqlError)).asRight.some)
          case InternalError(err) => send(Error(id, mkGraphqlErrors(err)).asRight.some)
        }
        (this, action)
      }

      override def stop(id: String): (ConnectionState[F], F[Unit]) =
        (this, subscriptions.remove(id))

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
              case Right(data) => send(data.asRight.some) *> send(FromServer.Complete(id).asRight.some)
              case Left(error) => send(error.asRight.some)
            }
          }

      override def close(reason: Option[GraphQLWSError]): (ConnectionState[F], F[Unit]) =
        (closed, subscriptions.removeAll *> send(reason.map(_.asLeft)))
    }

  /** Closed state.  All requests raise an error, the connection having been closed. */
  def closed[F[_]](implicit M: MonadError[F, Throwable]): ConnectionState[F] =

    new ConnectionState[F] {

      private val raiseError: (ConnectionState[F], F[Unit]) =
        (this, M.raiseError(new RuntimeException("Connection was terminated.")))

      override def reset(
        service: GraphQLService[F],
        r: Option[Either[GraphQLWSError, FromServer]] => F[Unit],
        s: Subscriptions[F]
      ): (ConnectionState[F], F[Unit]) =
        raiseError

      override def start(id: String, req: GraphQLRequest[JsonObject]): (ConnectionState[F], F[Unit]) =
        raiseError

      override def stop(id: String): (ConnectionState[F], F[Unit]) =
        raiseError

      override def close(reason: Option[GraphQLWSError]): (ConnectionState[F], F[Unit]) =
        (this, M.unit)
    }


  def apply[F[_]: Logger: Trace](
    service: Option[Authorization] => F[Option[GraphQLService[F]]],
    replyQueue: Queue[F, Option[Either[GraphQLWSError, FromServer]]]
  )(implicit F: Concurrent[F]): F[Connection[F]] =

    Ref.of(pendingInit[F](replyQueue)).map { stateRef =>

      new Connection[F] {

        def handle(f: ConnectionState[F] => (ConnectionState[F], F[Unit])): F[Unit] =
          stateRef.modify(f).flatten

        def parseAuthorization(
          connectionProps: JsonObject
        ): Option[ParseResult[Authorization]] =
          connectionProps("Authorization")
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
        def init(connectionProps: Option[JsonObject]): F[Unit] = Trace[F].span("connection.init") {

          // Creates the function used to send replies to the client. It just offers a message to
          // the reply queue and logs it.
          val reply: Option[Either[GraphQLWSError, FromServer]] => F[Unit] = { m =>
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
                Subscriptions(msg => reply(msg.map(_.asRight))).flatMap(s => handle(_.reset(svc, reply, s)))

              // User has insufficient privileges to connect.
              case None =>
                  handle(_.close(GraphQLWSError.Forbidden("Insufficient privileges").some))

            }

          // Either subscribe or error out, based on the Authorization property (if any)
          connectionProps.flatMap(parseAuthorization) match {

            // Authorization header is present and well-formed
            case Some(Right(auth)) =>
              trySubscribe(Some(auth))

            // Authorization header is missing
            case None =>
              trySubscribe(None)

            // Authorization header is present but malformed.
            case Some(Left(_)) =>
              handle(_.close(GraphQLWSError.Forbidden("Authorization property is malformed.").some))

          }

        }

        override def receive(m: FromClient): F[Unit] =
          Logger[F].debug(s"received $m") *> {
            m match {
              case ConnectionInit(m)       => init(m)
              case Subscribe(id, request)  => handle(_.start(id, request))
              case FromClient.Complete(id) => handle(_.stop(id))
              case Pong(_)                 => Logger[F].debug(s"Received Pong from client")
            }
          }

        override def close: F[Unit] =
          handle(_.close(none))
      }
    }
}
