// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.data.NonEmptyList
import cats.data.ValidatedNel
import cats.effect.*
import cats.effect.std.Queue
import cats.implicits.*
import clue.model.StreamingMessage.FromClient
import clue.model.json.given
import fs2.Pipe
import fs2.Stream
import grackle.Operation
import grackle.Result
import io.circe.*
import io.circe.syntax.*
import org.http4s.Header
import org.http4s.Headers
import org.http4s.HttpRoutes
import org.http4s.MediaType
import org.http4s.Method
import org.http4s.ParseFailure
import org.http4s.QueryParamDecoder
import org.http4s.Request
import org.http4s.Response
import org.http4s.Status
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Allow
import org.http4s.headers.Authorization
import org.http4s.headers.`Content-Type`
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame.Close
import org.http4s.websocket.WebSocketFrame.Ping
import org.http4s.websocket.WebSocketFrame.Pong
import org.http4s.websocket.WebSocketFrame.Text
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*
import org.typelevel.otel4s.trace.Tracer

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*

object Routes {

  def forService[F[_]: {Async, Logger as L, Tracer as T}](
    service:        Option[Authorization] => F[Option[GraphQLService[F]]],
    wsBuilder:      WebSocketBuilder2[F],
    graphQLPath:    String = "graphql",
    wsPath:         String = "ws",
    playgroundPath: String = "playground.html",
    keepAlive:      FiniteDuration = WsRouteHandler.DefaultKeepAlive,
  ): HttpRoutes[F] = {

    val dsl = new Http4sDsl[F]{}
    import dsl._

    // The handler depends on the service alone, so one instance serves every socket.
    val wsHandler = new WsRouteHandler(service, keepAlive)

    // The specification encodes the `variables` and the `extensions` parameters of a GET request
    // as a JSON object in a string.
    def jsonObjectDecoder(name: String): QueryParamDecoder[JsonObject] =
      QueryParamDecoder[String].emap { s =>
        parser.parse(s) match {
          case Left(ParsingFailure(msg, _)) => ParseFailure(s"The `$name` parameter is not valid JSON", msg).asLeft
          case Right(json) => json.asObject.toRight(ParseFailure(s"The `$name` parameter is not a JSON object", json.spaces2))
        }
      }

    object QueryMatcher         extends QueryParamDecoderMatcher[String]("query")
    object OperationNameMatcher extends OptionalQueryParamDecoderMatcher[String]("operationName")
    object VariablesMatcher     extends OptionalValidatingQueryParamDecoderMatcher[JsonObject]("variables")(using jsonObjectDecoder("variables"))
    object ExtensionsMatcher    extends OptionalValidatingQueryParamDecoderMatcher[JsonObject]("extensions")(using jsonObjectDecoder("extensions"))

    // The specification requires a well-formed GraphQL response body for the GraphQL media type at
    // every status. An unexpected error gives status 500 with a generic message and no data. The
    // cause goes to the log, not to the client.
    def internalErrorResponse(t: ResponseMediaType)(err: Throwable): F[Response[F]] =
      L.error(err)("Internal error in GraphQL request handling.") *>
        t.errorResponse[F](InternalServerError, "Internal server error.").pure[F]

    // Select the media type of the response. The specification requires status 406 when the
    // server supports no media type that the client accepts. This is the error boundary of every
    // HTTP GraphQL route, because it is the first point that knows the media type of the response.
    def negotiated(req: Request[F])(use: ResponseMediaType => F[Response[F]]): F[Response[F]] =
      ResponseMediaType.negotiateOrError(req.headers).fold(
        NotAcceptable(_),
        t => use(t).handleErrorWith(internalErrorResponse(t))
      )

    // Select the response media type, then build a handler for the authorized service.
    def withHandler(req: Request[F])(use: HttpRouteHandler[F] => F[Response[F]]): F[Response[F]] =
      negotiated(req): t =>
        service(req.headers.get[Authorization]).flatMap {
          case Some(s) => use(new HttpRouteHandler(s, t))
          case None    => t.errorResponse[F](Forbidden, "Access denied.").pure[F]
        }

    def playground(rootPath: Path): F[Response[F]] =
      Ok(Playground((rootPath / graphQLPath).toString, (rootPath / wsPath).toString)).map(_.withContentType(`Content-Type`(MediaType.text.html)))

    HttpRoutes.of[F] {

      // GraphQL query is embedded in the URI query string when queried via GET
      case req @ GET -> Root / `graphQLPath` :?  QueryMatcher(query) +& OperationNameMatcher(op) +& VariablesMatcher(vars) +& ExtensionsMatcher(exts) =>
        T.span(s"GET /$graphQLPath").surround:
          debug"GET one off: query=$query, op=$op, vars=$vars, exts=$exts" *>
          withHandler(req)(_.oneOffGet(query, op, vars, exts))

      // A GET request without a `query` parameter is not a well-formed GraphQL-over-HTTP request.
      // The specification asks for status 422.
      case req @ GET -> Root / `graphQLPath` =>
        T.span(s"GET /$graphQLPath").surround:
          debug"GET one off: no query parameter" *>
          negotiated(req): t =>
            t.errorResponse[F](
              UnprocessableContent,
              "The request must have a `query` parameter."
            ).pure[F]

      // GraphQL query is embedded in a Json request body when queried via POST
      case req @ POST -> Root / `graphQLPath` =>
        T.span(s"POST /$graphQLPath").surround:
          debug"POST one off: request=$req" *>
          withHandler(req)(_.oneOffPost(req))

      // WebSocket connection request.
      case req @ GET -> Root / `wsPath` =>
        T.span(s"GET /$wsPath").surround:
          debug"GET web socket: $req" *>
          wsHandler.webSocketConnection(wsBuilder)

      // GraphQL Playground
      case req @ GET -> Root / `playgroundPath` =>
        T.span(s"GET /$playgroundPath").surround:
          playground(Path(req.uri.path.segments.dropRight(Path.unsafeFromString(playgroundPath).segments.length)).toAbsolute)

      // The specification asks for status 405 when the request uses an unsupported method. RFC
      // 9110 requires the `Allow` header with this status.
      case req @ _ -> Root / `graphQLPath` =>
        T.span(s"${req.method} /$graphQLPath").surround:
          debug"Unsupported method ${req.method}" *>
          negotiated(req): t =>
            t.errorResponse[F](
              MethodNotAllowed,
              s"The method ${req.method.name} is not allowed. Use GET or POST."
            ).putHeaders(Allow(Method.GET, Method.POST)).pure[F]

    }
  }

}

class HttpRouteHandler[F[_]: {Temporal, Tracer}](
  service:      GraphQLService[F],
  acceptedType: ResponseMediaType
) {

  val dsl: Http4sDsl[F] = new Http4sDsl[F]{}
  import dsl._

  private def respond(status: Status, body: Json): F[Response[F]] =
    acceptedType.response[F](status, body).pure[F]

  // The status code of a GraphQL response, per the specification:
  //   - data and no errors      -> 200 Ok
  //   - data and errors         -> 294 Partial Success, or 200 Ok for a legacy client
  //   - no data                 -> `failureStatus`, which the caller selects from the cause
  private def statusFor(result: Result[Json], failureStatus: Status): Status =
    result match {
      case Result.Success(_)    => Ok
      case Result.Warning(_, _) => acceptedType.partialSuccessStatus
      case _                    => failureStatus
    }

  // Builds the response for a result. `mkResponse` raises the error of an internal error. The
  // route boundary in `Routes.forService` turns that error into status 500 with a GraphQL error
  // body.
  def toResponse(result: Result[Json], failureStatus: Status = UnprocessableContent): F[Response[F]] =
    service.mapping.mkResponse(result).flatMap(respond(statusFor(result, failureStatus), _))

  // A response with a single error message and no data.
  private def errorResponse(status: Status, message: String): F[Response[F]] =
    acceptedType.errorResponse[F](status, message).pure[F]

  // A response with several error messages and no data.
  private def errorResponse(status: Status, messages: NonEmptyList[String]): F[Response[F]] =
    acceptedType.errorResponse[F](status, messages).pure[F]

  // The specification asks for status 400 when the GraphQL document does not parse, and status
  // 422 when the document parses but the server cannot process the request.
  private def parseFailureStatus(document: String): Status =
    if service.parses(document) then UnprocessableContent else BadRequest

  // Returns a 422 Unprocessable Content response with a well-formed GraphQL JSON error body.
  // Used by both HTTP handlers to reject subscription operations.
  private def subscriptionRejection: F[Response[F]] =
    errorResponse(
      UnprocessableContent,
      "Subscription operations are not supported over HTTP. Use the WebSocket transport."
    )

  // Returns a 405 Method Not Allowed response with a well-formed GraphQL JSON error body. The
  // specification does not permit a mutation on a GET request. RFC 9110 requires the `Allow`
  // header with this status.
  private def mutationRejection: F[Response[F]] =
    errorResponse(
      MethodNotAllowed,
      "Mutation operations are not supported on a GET request. Use a POST request."
    ).map(_.putHeaders(Allow(Method.POST)))

  // The specification treats an error that execution raises as a field error. The response to a
  // field error must have a `data` entry and a 2xx status. A bare failure carries no value, so
  // this handler gives it the value `null` and lets `toResponse` select the 2xx status.
  private def executionResponse(result: Result[Json]): F[Response[F]] =
    toResponse(result match {
      case Result.Failure(problems) => Result.Warning(problems, Json.Null)
      case other                    => other
    })

  // Runs the operation and builds the response. A failure of the parse stage carries no
  // operation, so its status comes from the document. A failure of the execution stage is a
  // field error, which gives a 2xx status with a null `data` entry.
  private def execute(
    parsed:   Result[Operation],
    document: String
  )(run: Operation => F[Result[Json]]): F[Response[F]] =
    parsed match {
      case f: Result.Failure => toResponse(f, parseFailureStatus(document))
      case _                 => parsed.flatTraverse(run).flatMap(executionResponse)
    }

  // If the parsed operation is a subscription, return a 422 rejection immediately.
  // Otherwise invoke `proceed`.  Both Success and Warning carry an Operation value.
  private def rejectSubscription(
    parsed: Result[Operation]
  )(proceed: => F[Response[F]]): F[Response[F]] =
    parsed match {
      case Result.Success(op) if service.isSubscription(op)    => subscriptionRejection
      case Result.Warning(_, op) if service.isSubscription(op) => subscriptionRejection
      case _                                                    => proceed
    }

  def oneOffGet(
    query: String,
    op:    Option[String],
    vars0: Option[ValidatedNel[ParseFailure, JsonObject]],
    exts0: Option[ValidatedNel[ParseFailure, JsonObject]]
  ): F[Response[F]] =
    (vars0.sequence, exts0.sequence).tupled.fold(
      // A `variables` or `extensions` parameter that is not a JSON object is not a well-formed
      // GraphQL-over-HTTP request. The specification asks for status 422. The response reports
      // the errors of both parameters.
      errors => errorResponse(UnprocessableContent, errors.map(_.sanitized)),
      (vars, exts) => {
        val parsed = service.parse(query, op, vars)
        rejectSubscription(parsed) {
          parsed match {
            // Per the GraphQL over HTTP spec, GET requests MUST NOT execute mutations.
            case Result.Success(operation)    if service.isMutation(operation) => mutationRejection
            case Result.Warning(_, operation) if service.isMutation(operation) => mutationRejection
            // Re-parent server spans on the remote context in `extensions`, as POST does.
            case _ => execute(parsed, query)(p => joinRemote(exts)(service.query(p, query, op)))
          }
        }
      }
    )

  def oneOffPost(req: Request[F]): F[Response[F]] =
    // The specification requires support for `application/json` request bodies, and recommends
    // status 415 for any other media type and for an absent header.
    req.headers.get[`Content-Type`].map(_.mediaType) match {
      case Some(mt) if ResponseMediaType.Json.satisfiedBy(mt) => post(req)
      case Some(mt) => errorResponse(UnsupportedMediaType, s"Unsupported content type '$mt'. Use '${ResponseMediaType.Json}'.")
      case None     => errorResponse(UnsupportedMediaType, s"A Content-Type header of '${ResponseMediaType.Json}' is required.")
    }

  private def post(req: Request[F]): F[Response[F]] =
    req.attemptAs[Json].value.flatMap {
      // The specification asks for status 400 when the JSON body of the request does not parse.
      case Left(failure) => errorResponse(BadRequest, failure.message)
      case Right(body)   => postBody(body)
    }

  // Reads an optional request parameter of the body. The specification gives a type to each
  // parameter, and a parameter with the value `null` counts as absent. A parameter of the wrong
  // type makes the request not well-formed.
  private def optionalParam[A](
    obj:  JsonObject,
    name: String,
    tpe:  String
  )(read: Json => Option[A]): Either[NonEmptyList[String], Option[A]] =
    obj(name).filterNot(_.isNull) match {
      case None       => none.asRight
      case Some(json) => read(json).map(_.some).toRight(NonEmptyList.one(s"The `$name` entry must be $tpe."))
    }

  private def postBody(body: Json): F[Response[F]] = {

    // A body that is not a JSON object, or that has no `query` entry of type string, is not a
    // well-formed GraphQL-over-HTTP request. The specification asks for status 422.
    val request = body.asObject.toRight(NonEmptyList.one("The request body must be a JSON object.")).flatMap(obj =>
      (
        obj("query").flatMap(_.asString).toRight(NonEmptyList.one("The request body must have a `query` entry of type string.")),
        optionalParam(obj, "operationName", "a string")(_.asString),
        optionalParam(obj, "variables", "a JSON object")(_.asObject),
        optionalParam(obj, "extensions", "a JSON object")(_.asObject)
      ).parTupled
    )

    request.fold(
      messages => errorResponse(UnprocessableContent, messages),
      (query, op, vars, ext) => {
        val parsed = service.parse(query, op, vars)
        rejectSubscription(parsed) {
          execute(parsed, query)(p => joinRemote(ext)(service.query(p, query, op)))
        }
      }
    )
  }

}

object WsRouteHandler {

  /** The interval between two `ping` frames, and the limit for the `pong` reply of the client. */
  val DefaultKeepAlive: FiniteDuration =
    12.seconds

  /** The `ping` frame of the heartbeat. It carries no payload, so all sockets share one instance. */
  private[routes] val PingFrame: WebSocketFrame =
    Ping()

  /** The subprotocol of the socket, as the `Sec-WebSocket-Protocol` header of the handshake. */
  private[routes] val SubprotocolHeaders: Headers =
    Headers(Header.Raw(CIString("Sec-WebSocket-Protocol"), "graphql-transport-ws"))

  // The connection_init message payload has authorization information
  // which should not be logged.
  private val AuthRegEx    = """("Authorization":)\s*"[^"]*"""".r.unanchored
  private val RedactedAuth = """$1 <REDACTED>"""

  /** Replaces the value of the `Authorization` property of a client message with a marker. */
  private def redactAuth(s: String): String =
    AuthRegEx.replaceFirstIn(s, RedactedAuth)

  /** Shortens a message for the log. */
  private def trimmed(s: String): String =
    if (s.length > 516) s"${s.take(512)} ..." else s

  // The protocol limits the reason of a close frame to 123 bytes of UTF-8.
  private val CloseReasonMaxBytes = 123
  private val Ellipsis            = "..."

  /**
   * Shortens the reason of a close frame to the limit of the protocol. A cut can fall inside a
   * character of several bytes, and the decoder drops the incomplete sequence that it leaves. A
   * reason over the limit produces no frame at all, so every reason passes through here.
   */
  private def closeReason(s: String): String =
    val bytes = s.getBytes(UTF_8)
    if bytes.length <= CloseReasonMaxBytes then s
    else
      UTF_8
        .newDecoder
        .onMalformedInput(CodingErrorAction.IGNORE)
        .decode(ByteBuffer.wrap(bytes, 0, CloseReasonMaxBytes - Ellipsis.length))
        .toString + Ellipsis

}

class WsRouteHandler[F[_]: {Temporal as F, Logger as L, Tracer as T}](
  service:   Option[Authorization] => F[Option[GraphQLService[F]]],
  keepAlive: FiniteDuration = WsRouteHandler.DefaultKeepAlive
) {
  import WsRouteHandler.PingFrame
  import WsRouteHandler.SubprotocolHeaders
  import WsRouteHandler.closeReason
  import WsRouteHandler.redactAuth
  import WsRouteHandler.trimmed

  private def logWebSocketFrame(f: WebSocketFrame): F[Unit] =
    f match {
      case Text(s, last) => debug"Received Text frame (last=$last) from client: ${redactAuth(s)}"
      case _             => debug"Received message from client: $f"
    }

  // The frame for a reply, and the log line that goes with it. The message is encoded once.
  // `CloseWith` carries a code that the protocol reserves, so the close frame is well-formed.
  // A code that http4s rejects produces no frame, and the end of the reply stream still closes
  // the socket.
  private val toFrames: Pipe[F, Reply, WebSocketFrame] =
    _.evalMapFilter[F, WebSocketFrame] {
      case Reply.Send(m)        =>
        val s = m.asJson.noSpaces
        debug"Sending to client: ${trimmed(s)}".as(Text(s).some)
      case Reply.CloseWith(err) =>
        warn"Sending error to client: ${err.code} ${err.reason} - Closing connection"
          .as(Close(err.code, closeReason(err.reason)).orElse(Close(err.code)).toOption)
      case Reply.End            =>
        debug"Ending the reply stream - Closing connection".as(none)
    }

  // Sends one `ping` frame per interval, and closes the connection if no `pong` answers in time.
  // The synchronous queue hands off each frame, so a slow client delays only its own interval.
  // A socket that takes no frame at all is dead, so this completes `stalled` instead, to
  // interrupt the stream from outside.
  private[routes] def heartbeat(
    close:        F[Unit],
    stalled:      Deferred[F, Either[Throwable, Unit]],
    pongReceived: Ref[F, Boolean]
  ): Stream[F, WebSocketFrame] =
    Stream.eval(Queue.synchronous[F, WebSocketFrame]).flatMap: frames =>
      val handOff: F[Boolean] =
        F.timeoutTo(
          (debug"Sending a Ping frame" *> frames.offer(PingFrame)).as(true),
          keepAlive,
          warn"The socket took no Ping frame for a whole interval - aborting the connection" *>
            close *> stalled.complete(().asRight).as(false)
        )

      def cycle: F[Unit] =
        pongReceived.set(false) *> handOff.ifM(
          F.sleep(keepAlive) *> pongReceived.get.ifM(
            cycle,
            warn"The client sent no Pong frame for a whole interval - closing the connection" *> close
          ),
          F.unit
        )

      Stream
        .repeatEval(frames.take)
        .concurrently(Stream.exec(F.sleep(keepAlive) *> cycle))

  def webSocketConnection(wsb: WebSocketBuilder2[F]): F[Response[F]] = T.span("graphql.routes.webSocketConnection").surround {

    // Replies to the client. A terminal reply ends the stream, which closes the socket.
    def replies(replyQueue: Queue[F, Reply]): Stream[F, WebSocketFrame] =
      Stream
        .fromQueueUnterminated(replyQueue)
        .takeThrough(!_.isTerminal)
        .through(toFrames)

    // An invalid frame closes the connection with the reserved protocol code. The close goes
    // through the connection, so the state machine stops every subscription and ignores later
    // messages.
    def handle(connection: Connection[F])(frame: WebSocketFrame): F[Unit] =
      def closeInvalid(detail: String): F[Unit] =
        connection.closeWith(GraphQLWSError.InvalidMessage(detail))

      frame match {
        case Text(s, _) =>
          Either.catchNonFatal(parser.decode[FromClient](s)).flatten.fold(
            e => closeInvalid(e.getMessage),
            m => connection.receive(m)
          )

        case Close(_)   =>
          connection.close

        case f          =>
          closeInvalid(s"expected a text frame, got ${f.getClass.getSimpleName}")
      }

    // Input from the client. A `pong` frame sets the heartbeat flag. Every other frame goes on
    // a queue, so slow work on one message does not delay the next `pong`, and one fiber keeps
    // the message order.
    def receive(connection: Connection[F], pongReceived: Ref[F, Boolean]): Pipe[F, WebSocketFrame, Nothing] =
      in =>
        Stream.eval(Queue.unbounded[F, WebSocketFrame]).flatMap: messages =>
          in.evalTap(logWebSocketFrame)
            .foreach {
              // rfc6455 requires the reply to echo the payload of the `ping`, which is empty. A
              // `pong` that the client sends on its own can carry any payload, and it proves
              // nothing about the delivery of our `ping`, so it does not count.
              case Pong(data) =>
                pongReceived.set(true).whenA(data.isEmpty)

              // http4s answers a `ping` frame of the client with a `pong` frame.
              case Ping(_) =>
                F.unit

              case f       =>
                messages.offer(f)
            }
            .concurrently(Stream.fromQueueUnterminated(messages).foreach(handle(connection)))

    // The reply queue and the connection live inside the stream, so fs2 owns their lifetime and
    // an unopened socket leaks nothing. `mergeHaltBoth` ends the socket when either side ends,
    // which closes the connection and cancels every subscription. An abrupt disconnect takes the
    // same path.
    //
    // The heartbeat merges with `mergeHaltL`, so a missed `pong` closes through the reply queue,
    // and queued replies still reach the client first. A dead socket takes no frames, so it
    // completes `stalled` instead, to interrupt the stream from outside.
    val sendReceive: Pipe[F, WebSocketFrame, WebSocketFrame] = in =>
      for {
        replyQueue   <- Stream.eval(Queue.unbounded[F, Reply])
        pongReceived <- Stream.eval(Ref.of[F, Boolean](false))
        stalled      <- Stream.eval(Deferred[F, Either[Throwable, Unit]])
        connection   <- Stream.resource(Connection(service, replyQueue))
        frame        <- replies(replyQueue)
                          .mergeHaltL(heartbeat(connection.close, stalled, pongReceived))
                          .mergeHaltBoth(in.through(receive(connection, pongReceived)))
                          .interruptWhen(stalled)
      } yield frame

    wsb
      .withFilterPingPongs(false)
      .withHeaders(SubprotocolHeaders)
      .build(sendReceive)
  }

}
