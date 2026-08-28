// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadError
import cats.MonadThrow
import cats.effect.Deferred
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.Temporal
import cats.effect.std.Queue
import cats.effect.std.Supervisor
import cats.effect.syntax.all.*
import cats.syntax.all.*
import clue.model.GraphQLRequest
import clue.model.StreamingMessage.*
import clue.model.StreamingMessage.FromClient.*
import clue.model.StreamingMessage.FromServer.*
import fs2.Stream
import grackle.Operation
import grackle.Result
import grackle.Result.*
import io.circe.Json
import io.circe.JsonObject
import org.http4s.ParseResult
import org.http4s.headers.Authorization
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*

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
   * `Connected` to `Closed` as it receives messages from the client.
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
      send: Reply => F[Unit],
      subs: Subscriptions[F]
    ): (ConnectionState[F], F[Unit])

    /**
     * Starts a GraphQL operation associated with a particular id.
     * @return state transition and action to execute. The action returns the error that must
     *         close the connection, if any.
     */
    def start(id:  String, req: GraphQLRequest[JsonObject]): (ConnectionState[F], F[Option[GraphQLWSError]])

    /**
     * Stops the GraphQL operation associated with a particular id
     * @return state transition and action to execute
     */
    def stop(id: String): (ConnectionState[F], F[Unit])

    /**
     * Closes the connection, signaling that nothing more will be sent via the reply queue.
     * @return state transition and action
     */
    def close(reason: Option[GraphQLWSError]): (ConnectionState[F], F[Unit])

    /**
     * Handles the expiry of the wait time for `connection_init`.
     * @return state transition and action to execute
     */
    def initTimedOut: (ConnectionState[F], F[Unit])

  }

  // The last reply of a connection. It ends the reply stream, which closes the socket.
  private def lastReply(reason: Option[GraphQLWSError]): Reply =
    reason.fold(Reply.End)(Reply.CloseWith(_))

  /**
   * PendingInit state. Initial state, awaiting `connection_init` message that contains the user
   * authorization header. Once it receives it, we transition to `Connected`.
   */
  def pendingInit[F[_]: Logger: Tracer](
    replyQueue: Queue[F, Reply]
  )(implicit ev: MonadError[F, Throwable]): ConnectionState[F] =

    new ConnectionState[F] {

      override def reset(
        service: GraphQLService[F],
        send: Reply => F[Unit],
        subs: Subscriptions[F],
      ): (ConnectionState[F], F[Unit]) =
        (connected(service, send, subs),
         send(Reply.Send(ConnectionAck())) *> send(Reply.Send(FromServer.Ping()))
        )


      override def start(id: String, req: GraphQLRequest[JsonObject]): (ConnectionState[F], F[Option[GraphQLWSError]]) =
        doClose(s"start($id, $req)").map(_.as(none))

      override def stop(id: String): (ConnectionState[F], F[Unit]) =
        doClose(s"stop($id)")

      private def doClose(m: String): (ConnectionState[F], F[Unit]) =
        close(GraphQLWSError.Unauthorized(m).some)

      override def close(reason: Option[GraphQLWSError]): (ConnectionState[F], F[Unit]) =
        (closed, replyQueue.offer(lastReply(reason)))

      override def initTimedOut: (ConnectionState[F], F[Unit]) =
        close(GraphQLWSError.InitializationTimeout.some)

    }

  /**
   * Connected state. Post-initialization, we stay in the `Connected` state until explicitly
   * terminated by the client.
   */
  def connected[F[_]: {Logger, MonadThrow, Tracer as T}](
    service:       GraphQLService[F],
    send:          Reply => F[Unit],
    subscriptions: Subscriptions[F]
  ): ConnectionState[F] =

    new ConnectionState[F] {

      override def reset(
        service: GraphQLService[F],
        r: Reply => F[Unit],
        s: Subscriptions[F]
      ): (ConnectionState[F], F[Unit]) =
        (connected(service, r, s),
          subscriptions.removeAll  *>
            r(Reply.Send(ConnectionAck())) *>
            r(Reply.Send(FromServer.Ping()))
        )

      override def start(id: String, raw: GraphQLRequest[JsonObject]): (ConnectionState[F], F[Option[GraphQLWSError]]) = {
        val document    = raw.query.value
        val parseResult = service.parse(document, raw.operationName, raw.variables)
        val name        = raw.operationName
        // A subscription is its own event stream; a query or mutation is a one-element stream.
        def events(op: Operation): Stream[F, Result[Json]] =
          if (service.isSubscription(op)) service.subscribe(op, document, name)
          else Stream.eval(service.query(op, document, name))
        val action = parseResult match {
          case Success(op)        => startOperation(id, events(op))
          case Warning(_, op)     => startOperation(id, events(op)) // n.b. warnings on start are lost
          case Failure(ps)        => send(Reply.Send(Error(id, mkGraphqlErrors(ps)))).as(none)
          case InternalError(err) => send(Reply.Send(Error(id, mkGraphqlErrors(err)))).as(none)
        }
        // Re-parent server spans on the client's remote context
        // This is valid if the client span has a W3C traceparent value in extensions)
        (this, joinRemote(raw.extensions.traceCarrier)(action))
      }

      override def stop(id: String): (ConnectionState[F], F[Unit]) =
        (this, subscriptions.remove(id))

      // The attributes that the span of every client operation carries.
      private def inOperationSpan[A](id: String)(fa: F[A]): F[A] =
        T.withCurrentSpanOrNoop: span =>
          span.addAttributes(Attribute("connection.fromclient.id", id)) >>
            span.addAttributes(service.props*) >>
            fa

      // Every operation runs as an event stream in the subscription map, so a slow operation
      // does not block the receive loop and a client `complete` message can cancel it. The
      // protocol reserves close code 4409 for an id that is already active.
      def startOperation(id: String, events: Stream[F, Result[Json]]): F[Option[GraphQLWSError]] =
        inOperationSpan(id):
          subscriptions.add(id, events).map: added =>
            Option.unless(added)(GraphQLWSError.SubscriberAlreadyExists(id))

      override def close(reason: Option[GraphQLWSError]): (ConnectionState[F], F[Unit]) =
        (closed, subscriptions.removeAll *> send(lastReply(reason)))

      // `connection_init` arrived in time, so the expiry of the timer means nothing here.
      override def initTimedOut: (ConnectionState[F], F[Unit]) =
        (this, ().pure[F])
    }

  /**
   * Closed state. The connection put its last reply on the queue, so it ignores every message
   * that follows. A client can have messages in flight when the server closes the connection. An
   * error here fails the socket before the close frame reaches the client, and the client sees an
   * abnormal close with no code.
   */
  def closed[F[_]: {Logger, MonadThrow as M}]: ConnectionState[F] =

    new ConnectionState[F] {

      private def ignore[A](m: String)(a: A): (ConnectionState[F], F[A]) =
        (this, debug"Ignoring $m because the connection closed.".as(a))

      override def reset(
        service: GraphQLService[F],
        r: Reply => F[Unit],
        s: Subscriptions[F]
      ): (ConnectionState[F], F[Unit]) =
        ignore("connection_init")(())

      override def start(id: String, req: GraphQLRequest[JsonObject]): (ConnectionState[F], F[Option[GraphQLWSError]]) =
        ignore(s"subscribe($id)")(none[GraphQLWSError])

      override def stop(id: String): (ConnectionState[F], F[Unit]) =
        ignore(s"complete($id)")(())

      override def close(reason: Option[GraphQLWSError]): (ConnectionState[F], F[Unit]) =
        (this, M.unit)

      override def initTimedOut: (ConnectionState[F], F[Unit]) =
        ignore("connection_init timeout")(())
    }

  /** The wait time for `connection_init`. The protocol reserves close code 4408 for its expiry. */
  val ConnectionInitWaitTimeout: FiniteDuration = 10.seconds

  def apply[F[_]: {Temporal, Logger, Tracer as T}](
    service: Option[Authorization] => F[Option[GraphQLService[F]]],
    replyQueue: Queue[F, Reply]
  ): Resource[F, Connection[F]] =
    Supervisor[F].flatMap(supervisor => Resource.make(build(service, replyQueue, supervisor))(_.close))

  private def build[F[_]: {Temporal, Logger, Tracer as T}](
    service: Option[Authorization] => F[Option[GraphQLService[F]]],
    replyQueue: Queue[F, Reply],
    supervisor: Supervisor[F]
  ): F[Connection[F]] =

    (Ref.of(pendingInit[F](replyQueue)), Deferred[F, Unit]).flatMapN { (stateRef, initReceived) =>

      def handle[A](f: ConnectionState[F] => (ConnectionState[F], F[A])): F[A] =
        stateRef.modify(f).flatten

      /**
       * The timer for the `connection_init` message. If it expires, the connection is closed with code 4408. If the message arrives in time, the timer does nothing.
       */
      val initTimer: F[Unit] =
        initReceived.get.timeoutTo(ConnectionInitWaitTimeout, handle(_.initTimedOut))

      val connection = new Connection[F] {

        // The one way to send a reply to the client. It offers the reply to the queue and logs it.
        val reply: Reply => F[Unit] = { m =>
          replyQueue.offer(m) *> debug"Reply $m enqueued"
        }

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
        def init(connectionProps: Option[JsonObject]): F[Unit] = T.span("connection.init").surround {

          // Given an optional Authorization, get a service and start a subscription (if allowed)
          def trySubscribe(opAuth: Option[Authorization]): F[Unit] =
            service(opAuth).flatMap {

              // User is authorized. Go.
              case Some(svc) =>
                T.withCurrentSpanOrNoop:
                  _.addAttributes(svc.props*) >>
                    Subscriptions(supervisor, msg => reply(Reply.Send(msg)))
                      .flatMap(s => handle(_.reset(svc, reply, s)))

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
          debug"received $m" *> {
            m match {
              case ConnectionInit(m)       => initReceived.complete(()).void *> init(m)
              case Subscribe(id, request)  => handle(_.start(id, request)).flatMap(_.traverse_(e => handle(_.close(e.some))))
              case FromClient.Complete(id) => handle(_.stop(id))
              case FromClient.Ping(_)      => reply(Reply.Send(FromServer.Pong()))
              case FromClient.Pong(_)      => debug"Received Pong from client"
            }
          }

        override def close: F[Unit] =
          handle(_.close(none))
      }

      supervisor.supervise(initTimer).as(connection)
    }
}
