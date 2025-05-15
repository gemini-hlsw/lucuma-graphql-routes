// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.effect.std.Supervisor
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.IORuntimeConfig
import cats.implicits.*
import clue.FetchClient
import clue.GraphQLOperation
import clue.ResponseException
import clue.http4s.Http4sHttpBackend
import clue.http4s.Http4sHttpClient
import clue.http4s.Http4sWebSocketBackend
import clue.http4s.Http4sWebSocketClient
import clue.websocket.WebSocketClient
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.JsonObject
import munit.CatsEffectSuite
import munit.catseffect.IOFixture
import natchez.Trace.Implicits.noop
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.headers.Authorization
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.jdkhttpclient.JdkWSClient
import org.http4s.server.Server
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.{ Uri as Http4sUri, * }
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.SocketException
import scala.concurrent.duration.*

// This is a stripped-down version of OdbSuite
object BaseSuite:

  enum ClientOption:
    case Http,Ws
  
  object ClientOption:
    val all = values.toList

  def reportFailure: Throwable => Unit =
    case e if e.getMessage.startsWith("UNEXPECTED Error RECEIVED for subscription") => ()
    case e: IllegalArgumentException if e.getMessage == "statusCode" => ()
    case e: SocketException if e.getMessage == "Connection reset" => ()
    case e: ResponseException[Any]  @unchecked => ()
    case e => print("OdbSuite.reportFailure: "); e.printStackTrace

  // a runtime that is constructed the same as global, but lets us see unhandled errors (above)
  val runtime: IORuntime =
    val (compute, _) = IORuntime.createWorkStealingComputeThreadPool(reportFailure = reportFailure)
    val (blocking, _) = IORuntime.createDefaultBlockingExecutionContext()
    val (scheduler, _) = IORuntime.createDefaultScheduler()
    IORuntime(compute, blocking, scheduler, () => (), IORuntimeConfig())

abstract class BaseSuite extends CatsEffectSuite:
  import BaseSuite.ClientOption

  /* Subclasses must implement. */
  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]]

  override lazy val munitIoRuntime: IORuntime = BaseSuite.runtime
  given Logger[IO] = Slf4jLogger.getLoggerFromName("lucuma-odb-test")

  private def httpApp: Resource[IO, WebSocketBuilder2[IO] => HttpApp[IO]] =
    Resource.pure(Routes.forService(service, _).orNotFound)

  private def server: Resource[IO, Server] =
    httpApp.flatMap: app =>
      BlazeServerBuilder[IO]
        .withHttpWebSocketApp(app)
        .bindAny()
        .resource

  private def fetchClient(bearerToken: Option[String])(svr: Server): Resource[IO, FetchClient[IO, Nothing]] =
    for
      xbe <- JdkHttpClient.simple[IO].map(Http4sHttpBackend[IO](_))
      uri  = svr.baseUri / "graphql"
      hs   = Headers(bearerToken.toList.map(s => Authorization(Credentials.Token(AuthScheme.Bearer, s)))*)
      xc  <- Resource.eval(Http4sHttpClient.of[IO, Nothing](uri, headers = hs)(Async[IO], xbe, Logger[IO]))
    yield xc

  private def streamingClient(bearerToken: Option[String])(svr: Server): Resource[IO, WebSocketClient[IO, Nothing]] =
    for
      sbe <- JdkWSClient.simple[IO].map(Http4sWebSocketBackend[IO](_))
      uri  = (svr.baseUri / "ws").copy(scheme = Some(Http4sUri.Scheme.unsafeFromString("ws")))
      sc  <- Resource.eval(Http4sWebSocketClient.of[IO, Nothing](uri)(using Async[IO], Logger[IO], sbe))
      ps   = bearerToken.fold(Map.empty)(s => Map("Authorization" -> Json.fromString(s"Bearer $s")))
      _   <- Resource.make(sc.connect(ps.pure[IO]))(_ => sc.disconnect())
    yield sc

  private lazy val serverFixture: IOFixture[Server] =
    ResourceSuiteLocalFixture("server", server)

  override def munitFixtures = 
    List(serverFixture)

  private case class Operation(document: String) extends GraphQLOperation.Typed[Nothing, JsonObject, Json]

  def connection(cop: ClientOption, bearerToken: Option[String]): Server => Resource[IO, FetchClient[IO, Nothing]] =
    cop match
      case ClientOption.Http => s => fetchClient(bearerToken)(s)
      case ClientOption.Ws   => streamingClient(bearerToken)

  def expect(
    bearerToken: Option[String],
    query:       String,
    expected:    Either[List[String], Json],
    variables:   Option[JsonObject] = None,
    client:      ClientOption
  ): IO[Unit] =
    val op = this.query(bearerToken, query, variables, client)
    expected.fold(
      errors  => op.intercept[ResponseException[Any]].map(_.errors.toList.map(_.message)).assertEquals(errors),
      success => op.map(_.spaces2).assertEquals(success.spaces2)
    )

  def query(
    bearerToken: Option[String],
    query:       String,
    variables:   Option[JsonObject],
    client:      ClientOption
  ): IO[Json] =
    Resource.eval(IO(serverFixture()))
      .flatMap(connection(client, bearerToken))
      .use: conn =>
        val req = conn.request(Operation(query))
        val op  = 
            variables.fold(req.apply)(req.withInput).raiseGraphQLErrors
        op

  def subscription(
    bearerToken: Option[String], 
    query: String, 
    mutations: Either[List[(String, Option[JsonObject])], IO[Any]], 
    variables: Option[JsonObject],
    onError: ResponseException[Json] => IO[Unit] = _ => IO.unit
  ): IO[List[Json]] =
    Supervisor[IO].use: sup =>
      Resource.eval(IO(serverFixture()))
        .flatMap(streamingClient(bearerToken))
        .use: conn =>
          val req = conn.subscribe(Operation(query))
          variables
            .fold(req.apply)(req.withInput)
            .raiseFirstNoDataError
            .handleGraphQLErrors(onError)
            .allocated
            .flatMap: (sub, cleanup) =>
              for
                fib <- sup.supervise(sub.compile.toList)
                _   <- IO.sleep(100.millis)
                _   <- mutations.fold(_.traverse_ { case (query, vars) =>
                  val req = conn.request(Operation(query))
                  vars.fold(req.apply)(req.withInput)
                }, identity)
                _   <- IO.sleep(100.millis)
                _   <- cleanup
                obt <- fib.joinWithNever
              yield obt

  def subscriptionExpect(
    bearerToken: Option[String], 
    query: String, 
    mutations: Either[List[(String, Option[JsonObject])], IO[Any]], 
    expected: List[Json], 
    variables: Option[JsonObject]
  ): IO[Unit] =
    subscription(bearerToken, query, mutations, variables).map: obt =>
      assertEquals(obt.map(_.spaces2), expected.map(_.spaces2)) 

  def interceptGraphQL(messages: String*)(fa: IO[Any]): IO[Unit] =
    fa.attempt.flatMap:
      case Left(ResponseException(es, _)) => assertEquals(messages.toList, es.toList.map(_.message)).pure[IO]
      case Left(other)                    => IO.raiseError(other)
      case Right(a)                       => fail(s"Expected failure, got $a")
