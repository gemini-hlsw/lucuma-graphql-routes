// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.Applicative
import cats.Functor
import cats.Monad
import cats.MonadThrow
import cats.effect.Poll
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.Temporal
import cats.effect.std.Queue
import cats.effect.std.Supervisor
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
   * @param m
   *   client provided message
   */
  def receive(m: FromClient): F[Unit]

  /** Close the connection, never to be heard from again. */
  def close: F[Unit]

  /**
   * Close the connection with a protocol error, never to be heard from again. The client receives
   * the code and the reason of the error in the close frame.
   */
  def closeWith(reason: GraphQLWSError): F[Unit]

}

object Connection {

  /**
   * Connection is a state machine that (typically) transitions from states `PendingInit` to
   * `Initializing` to `Connected` to `Closed` as it receives messages from the client.
   */
  private enum ConnectionState[F[_]]:

    /**
     * A transition that keeps this state and runs `action`. The action cannot close the connection.
     */
    def staying(action: F[Unit])(using Functor[F]): Transition[F] =
      (this, action.as(none[GraphQLWSError]))

    /**
     * Initial state. It waits for the `connection_init` message that (optionally) carries the user
     * authorization header.
     */
    case PendingInit()

    /**
     * The `connection_init` message arrived and the service lookup for its authorization data is in
     * flight.
     */
    case Initializing()

    /**
     * Post-initialization state. It lasts until the client or the server closes the connection.
     */
    case Connected(service: GraphQLService[F], subscriptions: Subscriptions[F])

    /**
     * Final state. The connection put its last reply on the queue, so it ignores every event that
     * follows. A client can have messages in flight when the server closes the connection.
     */
    case Closed()

  /** An event that drives the state machine. */
  private enum Event[F[_]]:

    /**
     * A `connection_init` message from a client. The service lookup for its authorization data has
     * not run yet. The protocol allows one such message per connection.
     */
    case InitRequested(connectionProps: Option[JsonObject])

    /** The service lookup finished and the service authorized the client. */
    case Initialized(service: GraphQLService[F], subscriptions: Subscriptions[F])

    /** A `subscribe` message. It starts a GraphQL operation with a client-provided id. */
    case Start(id: String, request: GraphQLRequest[JsonObject])

    /** A `complete` message. It stops the operation of a client-provided id. */
    case Stop(id: String)

    /** Closes the connection and signals that nothing more goes on the reply queue. */
    case Close(reason: Option[GraphQLWSError])

    /** The wait time for `connection_init` expired. It only matters in `PendingInit`. */
    case InitTimedOut()

  /**
   * The result of one event: the next state, and the action that the transition runs. The action
   * returns the error that must close the connection, if any.
   */
  private type Transition[F[_]] = (ConnectionState[F], F[Option[GraphQLWSError]])

  /**
   * A transition to the final state. It runs `cleanup`, then puts the last reply on the queue. The
   * last reply ends the reply stream, which closes the socket.
   */
  private def closing[F[_]: Applicative](send: Reply => F[Unit], cleanup: F[Unit])(
    reason: Option[GraphQLWSError]
  ): Transition[F] =
    (ConnectionState.Closed(),
     (cleanup *> send(reason.fold(Reply.End)(Reply.CloseWith(_)))).as(none[GraphQLWSError])
    )

  /** Handles an event of the `PendingInit` state. */
  private def handlePendingInit[F[_]: {Monad as F}](
    send:      Reply => F[Unit],
    authorize: Option[JsonObject] => F[Option[GraphQLWSError]],
    event:     Event[F]
  ): Transition[F] = {

    def close = closing(send, F.unit)

    event match {

      // `>>` defers the lookup, because `flatModifyFull` can recompute this transition and the
      // lookup re-enters the state machine.
      case Event.InitRequested(connectionProps) =>
        (ConnectionState.Initializing(), F.unit >> authorize(connectionProps))

      // The lookup runs in `Initializing`, so no lookup can have finished yet.
      case Event.Initialized(_, _) =>
        close(GraphQLWSError.TooManyInitializationRequests.some)

      case Event.Start(id, _) =>
        close(GraphQLWSError.Unauthorized(s"start($id)").some)

      case Event.Stop(id) =>
        close(GraphQLWSError.Unauthorized(s"stop($id)").some)

      case Event.InitTimedOut() =>
        close(GraphQLWSError.InitializationTimeout.some)

      case Event.Close(reason) =>
        close(reason)

    }
  }

  /** Handles an event of the `Initializing` state. */
  private def handleInitializing[F[_]: {Applicative as F}](
    send:  Reply => F[Unit],
    state: ConnectionState.Initializing[F],
    event: Event[F]
  ): Transition[F] = {

    def close = closing(send, F.unit)

    event match {

      case Event.Initialized(service, subscriptions) =>
        (ConnectionState.Connected(service, subscriptions),
         (send(Reply.Send(ConnectionAck())) *> send(Reply.Send(FromServer.Ping())))
           .as(none[GraphQLWSError])
        )

      // A second `connection_init`, sent while the first lookup is still in flight.
      case Event.InitRequested(_) =>
        close(GraphQLWSError.TooManyInitializationRequests.some)

      case Event.Start(id, _) =>
        close(GraphQLWSError.Unauthorized(s"start($id)").some)

      case Event.Stop(id) =>
        close(GraphQLWSError.Unauthorized(s"stop($id)").some)

      case Event.InitTimedOut() =>
        state.staying(F.unit)

      case Event.Close(reason) =>
        close(reason)

    }
  }

  /** Handles an event of the `Connected` state. */
  private def handleConnected[F[_]: {MonadThrow as F, Tracer}](
    send:  Reply => F[Unit],
    poll:  Poll[F],
    state: ConnectionState.Connected[F],
    event: Event[F]
  ): Transition[F] = {

    // A close stops every operation first. `flatModifyFull` can recompute this transition, so the
    // cleanup effect is built only on the branches that use it.
    def close = closing(send, state.subscriptions.removeAll)

    event match {

      // A second `connection_init`. A second `Initialized` cannot happen, because `Initializing`
      // closes the connection before a second lookup starts.
      case Event.InitRequested(_) | Event.Initialized(_, _) =>
        close(GraphQLWSError.TooManyInitializationRequests.some)

      case Event.Start(id, request) =>
        (state, startOperation(send, state, id, request))

      case Event.Stop(id) =>
        state.staying(poll(state.subscriptions.remove(id)))

      case Event.Close(reason) =>
        close(reason)

      case Event.InitTimedOut() =>
        state.staying(F.unit)

    }
  }

  /** Handles an event of the `Closed` state. Every such event is ignored. */
  private def handleClosed[F[_]: {Applicative as F, Logger}](
    state: ConnectionState.Closed[F],
    event: Event[F]
  ): Transition[F] = {

    def ignore(m: => String): Transition[F] =
      state.staying(debug"Ignoring $m because the connection closed.")

    event match {

      case Event.InitRequested(_)  => ignore("connection_init")
      case Event.Initialized(_, _) => ignore("connection_init result")
      case Event.Start(id, _)      => ignore(s"subscribe($id)")
      case Event.Stop(id)          => ignore(s"complete($id)")
      case Event.InitTimedOut()    => ignore("connection_init timeout")

      // A double close is normal, because the client and the server can both close it. It is silent.
      case Event.Close(_) => state.staying(F.unit)

    }
  }

  /**
   * Starts a GraphQL operation of the `Connected` state. Every operation runs as an event stream in
   * the subscription map, so a slow operation does not block the receive loop and a client
   * `complete` message can cancel it.
   *
   * @return
   *   the error that must close the connection, if any. The protocol reserves close code 4409 for
   *   an id that is already active.
   */
  private def startOperation[F[_]: {MonadThrow as F, Tracer as T}](
    send:    Reply => F[Unit],
    state:   ConnectionState.Connected[F],
    id:      String,
    request: GraphQLRequest[JsonObject]
  ): F[Option[GraphQLWSError]] =
    // `flatModifyFull` computes the transition inside the compare-and-set loop of the `Ref`. The
    // GraphQL compile is expensive, so `>>` keeps it out of a loop that can retry.
    F.unit >> {
      val service  = state.service
      val document = request.query.value
      val name     = request.operationName

      // A subscription is its own event stream. A query or a mutation is a one-element stream.
      def events(op: Operation): Stream[F, Result[Json]] =
        if (service.isSubscription(op)) service.subscribe(op, document, name)
        else Stream.eval(service.query(op, document, name))

      // The span of every client operation carries the id of the operation and the service props.
      def add(op: Operation): F[Option[GraphQLWSError]] =
        T.withCurrentSpanOrNoop: span =>
          span.addAttributes(service.props :+ Attribute("connection.fromclient.id", id)*) >>
            state.subscriptions
              .add(id, events(op))
              .map: added =>
                Option.unless(added)(GraphQLWSError.SubscriberAlreadyExists(id))

      val action: F[Option[GraphQLWSError]] =
        service.parse(document, name, request.variables) match {
          case Success(op)        => add(op)
          case Warning(_, op)     => add(op) // n.b. warnings on start are lost
          case Failure(ps)        => send(Reply.Send(Error(id, mkGraphqlErrors(ps)))).as(none[GraphQLWSError])
          case InternalError(err) => send(Reply.Send(Error(id, mkGraphqlErrors(err)))).as(none[GraphQLWSError])
        }

      // Re-parent server spans on the client's remote context, if `extensions` carries one.
      joinRemote(request.extensions)(action)
    }

  /** The wait time for `connection_init`. The protocol reserves close code 4408 for its expiry. */
  val ConnectionInitWaitTimeout: FiniteDuration = 10.seconds

  def apply[F[_]: {Temporal, Logger, Tracer as T}](
    service:    Option[Authorization] => F[Option[GraphQLService[F]]],
    replyQueue: Queue[F, Reply]
  ): Resource[F, Connection[F]] =
    Supervisor[F].flatMap(supervisor =>
      Resource.make(build(service, replyQueue, supervisor))(_.close)
    )

  private def build[F[_]: {Temporal as F, Logger, Tracer as T}](
    service:    Option[Authorization] => F[Option[GraphQLService[F]]],
    replyQueue: Queue[F, Reply],
    supervisor: Supervisor[F]
  ): F[Connection[F]] = {

    // The one way to send a reply to the client. It offers the reply to the queue and logs it.
    val reply: Reply => F[Unit] = { m =>
      replyQueue.offer(m) *> debug"Reply $m enqueued"
    }

    Ref.of[F, ConnectionState[F]](ConnectionState.PendingInit()).flatMap { stateRef =>
      /**
       * Applies an event to the state machine and runs the action of the transition. An action that
       * returns an error closes the connection with that error. A close returns no error, so the
       * recursion ends there. `flatModifyFull` makes the state change and the action one
       * uncancelable step. `poll` unmasks the two actions that call out of this library and can
       * block: the authorization lookup, and the cancellation of an operation.
       */
      def handle(event: Event[F]): F[Unit] =
        stateRef
          .flatModifyFull { (poll, state) =>
            state match {
              case ConnectionState.PendingInit()       => handlePendingInit(reply, cp => poll(authorize(cp)), event)
              case s @ ConnectionState.Initializing()  => handleInitializing(reply, s, event)
              case s @ ConnectionState.Connected(_, _) => handleConnected(reply, poll, s, event)
              case s @ ConnectionState.Closed()        => handleClosed(s, event)
            }
          }
          .flatMap(_.traverse_(err => handle(Event.Close(err.some))))

      def parseAuthorization(
        connectionProps: JsonObject
      ): Option[ParseResult[Authorization]] =
        connectionProps("Authorization")
          .flatMap(_.asString)
          .map(Authorization.parse)

      /**
       * Connection initialization upon receipt of a `connection_init` message. It runs as the
       * action of the `PendingInit` transition, because a state transition cannot run its effects:
       * the user lookup for the authentication data, and the creation of the Subscriptions object.
       * A successful lookup fires `Initialized`, which moves the machine to `Connected`.
       *
       * @param connectionProps properties extracted from the `connection_init` payload
       * @return the error that must close the connection, if any
       */
      def authorize(connectionProps: Option[JsonObject]): F[Option[GraphQLWSError]] =
        T.span("connection.init").surround {

          // Given an optional Authorization, get a service and start a subscription (if allowed)
          def trySubscribe(opAuth: Option[Authorization]): F[Option[GraphQLWSError]] =
            service(opAuth).flatMap {

              // User is authorized. Go.
              case Some(svc) =>
                T.withCurrentSpanOrNoop:
                  _.addAttributes(svc.props*) >>
                    Subscriptions(supervisor, msg => reply(Reply.Send(msg)))
                      .flatMap(s => handle(Event.Initialized(svc, s)))
                      .as(none[GraphQLWSError])

              // User has insufficient privileges to connect.
              case None =>
                GraphQLWSError.Forbidden("Insufficient privileges").some.pure[F]

            }

          // Either subscribe or error out, based on the Authorization property (if any)
          connectionProps.flatMap(parseAuthorization) match {

            // Authorization header is present and well-formed
            case Some(Right(auth)) =>
              trySubscribe(auth.some)

            // Authorization header is missing
            case None =>
              trySubscribe(none)

            // Authorization header is present but malformed.
            case Some(Left(_)) =>
              GraphQLWSError.Forbidden("Authorization property is malformed.").some.pure[F]

          }

        }

      /** If the wait time expires before `connection_init` arrives, the connection closes. */
      val initTimer: F[Unit] = F.sleep(ConnectionInitWaitTimeout) *> handle(Event.InitTimedOut())

      supervisor
        .supervise(initTimer)
        .map: initTimerF =>
          new Connection[F] {

            override def receive(m: FromClient): F[Unit] =
              debug"received $m" *> {
                m match {
                  case ConnectionInit(m)       => initTimerF.cancel *> handle(Event.InitRequested(m))
                  case Subscribe(id, request)  => handle(Event.Start(id, request))
                  case FromClient.Complete(id) => handle(Event.Stop(id))
                  case FromClient.Ping(_)      => reply(Reply.Send(FromServer.Pong()))
                  case FromClient.Pong(_)      => debug"Received Pong from client"
                }
              }

            override def close: F[Unit] =
              handle(Event.Close(none))

            override def closeWith(reason: GraphQLWSError): F[Unit] =
              handle(Event.Close(reason.some))
          }
    }
  }
}
