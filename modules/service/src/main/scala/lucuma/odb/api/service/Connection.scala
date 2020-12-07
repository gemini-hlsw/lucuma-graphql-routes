// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.service

import lucuma.odb.api.service.ErrorFormatter.syntax._
import lucuma.core.model.User
import lucuma.sso.client.SsoClient
import cats.{Applicative, MonadError}
import cats.effect.concurrent.Ref
import cats.effect.ConcurrentEffect
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import clue.model.GraphQLRequest
import clue.model.StreamingMessage._
import clue.model.StreamingMessage.FromClient._
import clue.model.StreamingMessage.FromServer._
import fs2.concurrent.NoneTerminatedQueue
import io.circe.Json
import org.http4s.headers.Authorization
import org.log4s.getLogger
import sangria.parser.QueryParser

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

}

object Connection {

  import syntax.json._

  private[this] val logger = getLogger

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
    def init(
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
     * Terminates the connection, closing all ongoing subscriptions as well.
     *
     * @return state transition and action to execute
     */
    def terminate: (ConnectionState[F], F[Unit])
  }

  /**
   * PendingInit state.  Initial state, awaiting `connection_init` message that
   * contains the user authorization header.  Once it receives it, we transition
   * to `Connected`.
   */
  final class PendingInit[F[_]](
    odbService: OdbService[F],
    replyQueue: NoneTerminatedQueue[F, FromServer]
  )(implicit F: ConcurrentEffect[F]) extends ConnectionState[F] {

    override def user: Option[User] =
      None

    override def init(
      user: Option[User],
      send: Option[FromServer] => F[Unit],
      subs: Subscriptions[F]
    ): (ConnectionState[F], F[Unit]) =
      (new Connected(odbService, user, send, subs),
       send(Some(ConnectionAck)) *> send(Some(ConnectionKeepAlive))
      )

    private def doTerminate(m: String): (ConnectionState[F], F[Unit]) =
      (new Terminated(None),
       for {
         _ <- F.delay(logger.info(s"Request received on un-initialized connection: $m. Terminating."))
         _ <- replyQueue.enqueue1(None)
       } yield ()
      )

    override def start(id: String, req: GraphQLRequest): (ConnectionState[F], F[Unit]) =
      doTerminate(s"start($id, $req)")

    override def stop(id: String): (ConnectionState[F], F[Unit]) =
      doTerminate(s"stop($id)")

    override def terminate: (ConnectionState[F], F[Unit]) =
      doTerminate("terminate")
  }

  /**
   * Connected state. Post-initialization, we stay in the `Connected` state
   * until explicitly terminated by the client.
   */
  final class Connected[F[_]](
    odbService:        OdbService[F],
    override val user: Option[User],
    send:              Option[FromServer] => F[Unit],
    subscriptions:     Subscriptions[F]
  )(implicit F: ConcurrentEffect[F]) extends ConnectionState[F] {

    def info(m: String): F[Unit] =
      F.delay(logger.info(s"user=$user, message=$m"))

    override def init(
      u: Option[User],
      r: Option[FromServer] => F[Unit],
      s: Subscriptions[F]
    ): (ConnectionState[F], F[Unit]) =
      terminate.map { action =>
        for {
          _ <- info("received connection_init on already initialized connection")
          _ <- action
        } yield ()
      }

    override def start(id: String, raw: GraphQLRequest): (ConnectionState[F], F[Unit]) = {

      val parseResult =
        QueryParser
          .parse(raw.query)
          .toEither
          .map(ParsedGraphQLRequest(_, raw.operationName, raw.variables))

      val action = parseResult match {
        case Left(err)                        => send(Some(Error(id, err.format)))
        case Right(req) if req.isSubscription => subscribe(id, req)
        case Right(req)                       => execute(id, req)
      }

      (this, action)

    }

    override def stop(id: String): (ConnectionState[F], F[Unit]) =
      (this, subscriptions.remove(id))

    override val terminate: (ConnectionState[F], F[Unit]) =
      (new Terminated(user), subscriptions.terminate *> send(None))

    def subscribe(id: String, request: ParsedGraphQLRequest): F[Unit] =
      for {
        s <- odbService.subscribe(request)
        _ <- subscriptions.add(id, s)
      } yield ()

    def execute(id: String, request: ParsedGraphQLRequest): F[Unit] =
      for {
        r <- odbService.query(request)
        _ <- r.fold(
               err  => send(Some(Error(id, err.format))),
               json => send(Some(json.toStreamingMessage(id))) *> send(Some(Complete(id)))
             )
      } yield ()

  }

  /**
   * Terminated state.  All requests other than `terminate` raise an error, the
   * connection having been terminated.
   */
  final class Terminated[F[_]](
    override val user: Option[User]
  )(implicit F: Applicative[F], M: MonadError[F, Throwable]) extends ConnectionState[F] {

    private val raiseError: (ConnectionState[F], F[Unit]) =
      (this, M.raiseError(new RuntimeException(s"Connection (user=$user) was terminated")))

    override def init(
      u: Option[User],
      r: Option[FromServer] => F[Unit],
      s: Subscriptions[F]
    ): (ConnectionState[F], F[Unit]) =
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
    userClient: SsoClient[F, User],
    replyQueue: NoneTerminatedQueue[F, FromServer]
  )(implicit F: ConcurrentEffect[F]): F[Connection[F]] =

    Ref.of(new PendingInit[F](odbService, replyQueue): ConnectionState[F]).map { stateRef =>

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
              b <- replyQueue.offer1(m)
              _ <- F.delay(logger.info(s"Subscriptions send (user=$user) $m ${if (b) "enqueued" else "DROPPED!"}"))
            } yield ()
          }

          for {
            a <- parseConnectionProps(connectionProps)
            u <- a.fold(F.pure(Option.empty[User]))(userClient.get)
            r  = reply(u)
            s <- Subscriptions(u, r)
            _ <- handle(_.init(u, r, s))
          } yield ()

        }

        override def receive(m: FromClient): F[Unit] =
          m match {
            case ConnectionInit(m)   => init(m.toMap)
            case Start(id, request)  => handle(_.start(id, request))
            case Stop(id)            => handle(_.stop(id))
            case ConnectionTerminate => handle(_.terminate)
          }
      }
    }
}
