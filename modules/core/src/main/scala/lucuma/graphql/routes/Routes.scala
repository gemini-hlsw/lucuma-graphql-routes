// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.data.NonEmptyList
import cats.data.ValidatedNel
import cats.effect.*
import cats.effect.std.Queue
import cats.implicits.*
import clue.model.StreamingMessage.FromClient
import clue.model.StreamingMessage.FromServer
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
import org.http4s.websocket.WebSocketFrame.Text
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax.*
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*

object Routes {

  val KeepAliveDuration: FiniteDuration =
    5.seconds

  def forService[F[_]: {Logger, Async, Tracer as T}](
    service:        Option[Authorization] => F[Option[GraphQLService[F]]],
    wsBuilder:      WebSocketBuilder2[F],
    graphQLPath:    String = "graphql",
    wsPath:         String = "ws",
    playgroundPath: String = "playground.html",
  ): HttpRoutes[F] = {

    val dsl = new Http4sDsl[F]{}
    import dsl._

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

    // Select the media type of the response. The specification requires status 406 when the
    // server supports no media type that the client accepts.
    def negotiated(req: Request[F])(use: ResponseMediaType => F[Response[F]]): F[Response[F]] =
      ResponseMediaType.negotiateOrError(req.headers).fold(NotAcceptable(_), use)

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
          new WsRouteHandler(service).webSocketConnection(wsBuilder)

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

  // Builds the response for a result. `mkResponse` raises the error of an internal error, which
  // http4s turns into status 500.
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

  // Runs the operation and builds the response. A failure of the parse stage carries no
  // operation, so its status comes from the document instead of from the execution stage.
  private def execute(
    parsed:   Result[Operation],
    document: String
  )(run: Operation => F[Result[Json]]): F[Response[F]] =
    parsed match {
      case f: Result.Failure => toResponse(f, parseFailureStatus(document))
      case _                 => parsed.flatTraverse(run).flatMap(toResponse(_))
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
            case _ => execute(parsed, query)(p => joinRemote(exts.traceCarrier)(service.query(p, query, op)))
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
          execute(parsed, query)(p => joinRemote(ext.traceCarrier)(service.query(p, query, op)))
        }
      }
    )
  }

}

object WsRouteHandler {

  // The connection_init message payload has authorization information
  // which should not be logged.
  private val AuthRegEx    = """("Authorization":)\s*"[^"]*"""".r.unanchored
  private val RedactedAuth = """$1 <REDACTED>"""

  /** Replaces the value of the `Authorization` property of a client message with a marker. */
  private def redactAuth(s: String): String =
    AuthRegEx.replaceFirstIn(s, RedactedAuth)

}

class WsRouteHandler[F[_]: {Logger as L, Temporal, Tracer as T}](service: Option[Authorization] => F[Option[GraphQLService[F]]]) {
  import WsRouteHandler.redactAuth

  val KeepAliveDuration: FiniteDuration =
    5.seconds

  def webSocketConnection(wsb: WebSocketBuilder2[F]): F[Response[F]] = T.span("graphql.routes.webSocketConnection").surround {

    // The keepalive Ping is a constant, so it is encoded once for the lifetime of the handler.
    val pingFrame: WebSocketFrame =
      Text(FromServer.Ping().asJson.noSpaces)

    val keepAliveStream: Stream[F, Reply] =
      Stream
        .constant[F, Reply](Reply.Send(FromServer.Ping()))
        .metered(KeepAliveDuration)

    def logWebSocketFrame(f: WebSocketFrame): F[Unit] =
      f match {
        case Text(s, last) => debug"Received Text frame (last=$last) from client: ${redactAuth(s)}"
        case _             => debug"Received message from client: $f"
      }

    def trimmed(s: String): String =
      if (s.length > 516) s"${s.take(512)} ..." else s

    // The frame for a reply, and the log line that goes with it. The message is encoded once.
    // `CloseWith` carries a code that the protocol reserves, so the close frame is well-formed.
    // A code that http4s rejects produces no frame, and the end of the reply stream still closes
    // the socket.
    val toFrames: Pipe[F, Reply, WebSocketFrame] =
      _.evalMapFilter[F, WebSocketFrame] {
        case Reply.Send(FromServer.Ping(None)) =>
          debug"Sending Ping".as(pingFrame.some)
        case Reply.Send(m)                     =>
          val s = m.asJson.noSpaces
          debug"Sending to client: ${trimmed(s)}".as(Text(s).some)
        case Reply.CloseWith(err)              =>
          warn"Sending error to client: ${err.code} ${err.reason} - Closing connection"
            .as(Close(err.code, err.reason).orElse(Close(err.code)).toOption)
        case Reply.End                         =>
          debug"Ending the reply stream - Closing connection".as(none)
      }

    // Replies to the client. A terminal reply ends the stream, so `mergeHaltL` then stops the
    // keepalive stream, and no Ping follows the close frame.
    def replies(replyQueue: Queue[F, Reply]): Stream[F, WebSocketFrame] =
      Stream
        .fromQueueUnterminated(replyQueue)
        .takeThrough(!_.isTerminal)
        .through(toFrames)
        .mergeHaltL(keepAliveStream.through(toFrames))

    // Input from the client. A frame that the server cannot handle fails the stream.
    def receive(connection: Connection[F]): Pipe[F, WebSocketFrame, Nothing] =
      _.evalTap(logWebSocketFrame)
        .evalMap {
          case Text(s, _) =>
            Either.catchNonFatal(parser.decode[FromClient](s)).flatten.fold(
              e => Concurrent[F].raiseError[Unit](new RuntimeException(s"Could not parse client message $s as FromClient: $e")),
              m => connection.receive(m)
            )

          case Close(_)   =>
            connection.close

          case f          =>
            Concurrent[F].raiseError[Unit](new RuntimeException(s"Expected a Text WebSocketFrame from Client, but got $f"))
        }
        .drain

    // The reply queue and the connection live inside the stream, so fs2 owns their lifetime. A
    // build that never opens a socket leaks nothing, because the stream never runs.
    // `mergeHaltBoth` ends the socket when either side ends, which closes the connection and
    // cancels every subscription. An abrupt disconnect takes the same path.
    val sendReceive: Pipe[F, WebSocketFrame, WebSocketFrame] = in =>
      for {
        replyQueue <- Stream.eval(Queue.unbounded[F, Reply])
        connection <- Stream.resource(Connection(service, replyQueue))
        frame      <- replies(replyQueue).mergeHaltBoth(in.through(receive(connection)))
      } yield frame

    wsb
      .withHeaders(Headers(Header.Raw(CIString("Sec-WebSocket-Protocol"), "graphql-transport-ws")))
      .build(sendReceive)
  }

}
