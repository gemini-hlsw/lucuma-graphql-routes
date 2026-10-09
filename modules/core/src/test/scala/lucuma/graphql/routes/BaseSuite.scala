// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.effect.std.Queue
import cats.effect.std.Supervisor
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.IORuntimeConfig
import cats.implicits.*
import clue.FetchClient
import clue.GraphQLDocument
import clue.GraphQLOperation
import clue.ResponseException
import clue.http4s.Http4sHttpBackend
import clue.http4s.Http4sHttpClient
import clue.http4s.Http4sWebSocketBackend
import clue.http4s.Http4sWebSocketClient
import clue.websocket.WebSocketClient
import com.comcast.ip4s.port
import fs2.Stream
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.JsonObject
import io.circe.parser
import munit.CatsEffectSuite
import munit.catseffect.IOFixture
import org.http4s.MediaType.`application/graphql-response+json`
import org.http4s.client.Client
import org.http4s.client.websocket.WSClient
import org.http4s.client.websocket.WSFrame
import org.http4s.client.websocket.WSRequest
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Authorization
import org.http4s.headers.`Content-Type`
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.jdkhttpclient.JdkWSClient
import org.http4s.server.Server
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.{Uri as Http4sUri, *}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.otel4s.trace.Tracer

import java.net.SocketException
import scala.concurrent.duration.*

// This is a stripped-down version of OdbSuite
object BaseSuite:

  enum ClientOption:
    case Http, Ws

  object ClientOption:
    val all = values.toList

  def reportFailure: Throwable => Unit =
    case e if e.getMessage.startsWith("UNEXPECTED Error RECEIVED for subscription") => ()
    case e: IllegalArgumentException if e.getMessage == "statusCode" => ()
    case e: SocketException if e.getMessage == "Connection reset" => ()
    case e: ResponseException[Any]  @unchecked => ()
    case e => print("OdbSuite.reportFailure: "); e.printStackTrace

  val logger: Logger[IO] = Slf4jLogger.getLoggerFromName("lucuma-odb-test")

  // An in-process connection and its reply queue. There is no socket and no server, so a test
  // can send client messages to the connection and read the replies straight off the queue.
  def connectionResource(
    service:       GraphQLService[IO],
    authenticator: Authenticator[IO] = Authenticator.open[IO],
    config:        RoutesConfig = RoutesConfig.Default
  )(using Logger[IO], Tracer[IO]): Resource[IO, (Connection[IO], Queue[IO, Reply])] =
    for
      queue <- Resource.eval(Queue.unbounded[IO, Reply])
      conn  <- Connection[IO](new AuthResolver(service, authenticator, config), queue)
    yield (conn, queue)

  // a runtime that is constructed the same as global, but lets us see unhandled errors (above)
  val runtime: IORuntime =
    val (compute, _, _) = IORuntime.createWorkStealingComputeThreadPool(reportFailure = reportFailure)
    val (blocking, _) = IORuntime.createDefaultBlockingExecutionContext()
    val (scheduler, _) = IORuntime.createDefaultScheduler()
    IORuntime(compute, blocking, scheduler, () => (), IORuntimeConfig())

abstract class BaseSuite extends CatsEffectSuite:
  import BaseSuite.ClientOption

  /* Subclasses must implement. */
  def graphQLService: GraphQLService[IO]

  /* Subclasses override this when they test authentication. */
  def authenticator: Authenticator[IO] = Authenticator.open[IO]

  /* Subclasses override this when they test a policy, a path, or the keepalive interval. */
  def routesConfig: RoutesConfig = RoutesConfig.Default

  override lazy val munitIoRuntime: IORuntime = BaseSuite.runtime

  given Logger[IO] = BaseSuite.logger
  given Tracer[IO] = Tracer.noop[IO]

  private def httpApp: Resource[IO, WebSocketBuilder2[IO] => HttpApp[IO]] =
    Resource.pure(Routes.forService(graphQLService, authenticator, _, routesConfig).orNotFound)

  private def server: Resource[IO, Server] =
    httpApp.flatMap: app =>
      EmberServerBuilder
        .default[IO]
        .withPort(port"0")
        .withHttpWebSocketApp(app)
        .withShutdownTimeout(Duration.Zero)
        .build

  // The URI of the endpoint at the given path. The path can have more than one segment.
  protected def endpointUri(svr: Server, path: String): Http4sUri =
    svr.baseUri.withPath(svr.baseUri.path.concat(Http4sUri.Path.unsafeFromString(path)).toAbsolute)

  private def fetchClient(bearerToken: Option[String])(svr: Server): Resource[IO, FetchClient[IO, Nothing]] =
    val xbe = Http4sHttpBackend[IO](httpClientFixture())
    val uri = endpointUri(svr, routesConfig.graphQLPath)
    val hs  = Headers(bearerToken.toList.map(s => Authorization(Credentials.Token(AuthScheme.Bearer, s)))*)
    Resource.eval(Http4sHttpClient.of[IO, Nothing](uri, headers = hs)(using Async[IO], xbe, Logger[IO]))

  private def wsUri(svr: Server): Http4sUri =
    endpointUri(svr, routesConfig.wsPath).copy(scheme = Http4sUri.Scheme.unsafeFromString("ws").some)

  // A handshake request for the WebSocket endpoint, with the subprotocol that the server requires.
  protected def wsRequest(svr: Server): WSRequest =
    WSRequest(wsUri(svr)).withHeaders(WsRouteHandler.SubprotocolHeaders)

  protected def streamingClient(bearerToken: Option[String])(svr: Server): Resource[IO, WebSocketClient[IO, Nothing]] =
    val sbe = Http4sWebSocketBackend[IO](wsClientFixture())
    val uri = wsUri(svr)
    val ps  = bearerToken.fold(Map.empty)(s => Map("Authorization" -> Json.fromString(s"Bearer $s")))
    for
      sc <- Resource.eval(Http4sWebSocketClient.of[IO, Nothing](uri)(using Async[IO], Logger[IO], sbe))
      _  <- Resource.make(sc.connect(ps.pure[IO]))(_ => sc.disconnect())
    yield sc

  // Send a request to the GraphQL endpoint without any client-side interpretation, and return
  // the status, the headers and the body text of the response. Use this to assert on the parts
  // of the response that a GraphQL client hides, such as the status code and the media type.
  protected def rawResponse(mkRequest: Http4sUri => Request[IO]): IO[(Status, Headers, String)] =
    rawResponseAt(routesConfig.graphQLPath)(mkRequest)

  // Like `rawResponse`, for the endpoint at the given path.
  protected def rawResponseAt(path: String)(mkRequest: Http4sUri => Request[IO]): IO[(Status, Headers, String)] =
    Resource.eval(IO(serverFixture()))
      .flatMap(svr => httpClientFixture().run(mkRequest(endpointUri(svr, path))))
      .use(resp => resp.bodyText.compile.string.map((resp.status, resp.headers, _)))

  // Like `rawResponse`, with the body parsed as JSON. A body that is not JSON becomes `Json.Null`.
  protected def jsonResponse(mkRequest: Http4sUri => Request[IO]): IO[(Status, Headers, Json)] =
    rawResponse(mkRequest).map((status, headers, text) => (status, headers, parser.parse(text).getOrElse(Json.Null)))

  // GET the GraphQL endpoint with the given query parameters.
  protected def jsonGet(params: (String, String)*): IO[(Status, Headers, Json)] =
    jsonResponse(uri => Request[IO](Method.GET, uri.withQueryParams(params.toMap)))

  // The `errors` list of a response body, or an empty list if there is none.
  protected def errorsOf(body: Json): List[Json] =
    body.hcursor.downField("errors").as[List[Json]].getOrElse(Nil)

  // Assert that the response carries the GraphQL media type and a non-empty `errors` list, that
  // each error has a message, and that it carries no `data` entry.
  protected def assertErrorBody(headers: Headers, body: Json): Unit =
    val contentType = headers.get[`Content-Type`].map(_.mediaType)
    assertEquals(contentType, `application/graphql-response+json`.some)
    val errors = errorsOf(body)
    assert(errors.nonEmpty, s"Expected an errors list, got: ${body.spaces2}")
    assert(!body.hcursor.downField("data").succeeded, s"Expected no data, got: ${body.spaces2}")
    errors.foreach: error =>
      assert(error.hcursor.downField("message").as[String].isRight, s"Expected a message, got: ${error.spaces2}")

  // Opens a raw WebSocket to the WebSocket endpoint, sends the frames, and returns the first `count`
  // frames that the server sends. The stream stops early if the server closes the socket. Use this
  // to send frames that a GraphQL client cannot send, such as a fragment or a message that is not
  // valid JSON.
  protected def rawWsFrames(count: Int)(frames: WSFrame*): IO[List[WSFrame]] =
    Resource.eval(IO(serverFixture()))
      .flatMap(svr => wsClientFixture().connect(wsRequest(svr)))
      // The client queues every frame that arrives, so a send before the read loses nothing.
      .use(conn => conn.sendMany(frames.toList) *> conn.receiveStream.take(count.toLong).compile.toList)
      .timeout(10.seconds)

  protected lazy val serverFixture: IOFixture[Server] =
    ResourceSuiteLocalFixture("server", server)

  protected lazy val httpClientFixture: IOFixture[Client[IO]] =
    ResourceSuiteLocalFixture("http-client", JdkHttpClient.simple[IO])

  protected lazy val wsClientFixture: IOFixture[WSClient[IO]] =
    ResourceSuiteLocalFixture("ws-client", JdkWSClient.simple[IO])

  // The clients come first, so that munit closes them before it stops the server.
  override def munitFixtures =
    List(httpClientFixture, wsClientFixture, serverFixture)

  // Tests supply the query as a plain runtime `String`, so it skips the `gql` interpolator's
  // compile-time checks — there is nothing to check, no subqueries are spliced.
  protected case class Operation(query: String) extends GraphQLOperation.Typed[Nothing, JsonObject, Json]:
    override val document = GraphQLDocument.unsafeFromString(query)

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
    connection(client, bearerToken)(serverFixture())
      .use: conn =>
        val req = conn.request(Operation(query))
        val op  =
            variables.fold(req.apply)(req.withInput).raiseGraphQLErrors
        op

  // Allocates a subscription on an open connection. Returns the result stream and the
  // client-side cleanup action, which sends `complete` from client to server, separately.
  protected def allocateSubscription(
    conn:      WebSocketClient[IO, Nothing],
    query:     String,
    variables: Option[JsonObject],
    onError:   ResponseException[Json] => IO[Unit] = _ => IO.unit
  ): IO[(Stream[IO, Json], IO[Unit])] =
    val req = conn.subscribe(Operation(query))
    variables
      .fold(req.apply)(req.withInput)
      .raiseFirstNoDataError
      .handleGraphQLErrors(onError)
      .allocated

  // Opens a connection and allocates a subscription on it. The Resource manages the
  // connection lifetime. The returned cleanup action ends only the subscription.
  protected def openSubscription(
    bearerToken: Option[String],
    query:       String,
    variables:   Option[JsonObject]
  ): Resource[IO, (Stream[IO, Json], IO[Unit])] =
    streamingClient(bearerToken)(serverFixture())
      .evalMap(allocateSubscription(_, query, variables))

  def subscription(
    bearerToken: Option[String],
    query: String,
    mutations: Either[List[(String, Option[JsonObject])], IO[Any]],
    variables: Option[JsonObject],
    onError: ResponseException[Json] => IO[Unit] = _ => IO.unit
  ): IO[List[Json]] =
    Supervisor[IO].use: sup =>
      streamingClient(bearerToken)(serverFixture())
        .use: conn =>
          allocateSubscription(conn, query, variables, onError)
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
