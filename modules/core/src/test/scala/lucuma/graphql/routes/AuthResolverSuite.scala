// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import clue.model.StreamingMessage.FromClient
import grackle.Env
import io.circe.Json
import io.circe.JsonObject
import munit.CatsEffectSuite
import org.http4s.Credentials
import org.http4s.Header
import org.http4s.Headers
import org.http4s.headers.Authorization
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.testing.TestingLogger
import org.typelevel.log4cats.testing.TestingLogger.LogMessage
import org.typelevel.log4cats.testing.TestingLogger.WARN
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*

class AuthResolverSuite extends CatsEffectSuite:

  given Tracer[IO] = Tracer.noop[IO]

  private lazy val service: GraphQLService[IO] =
    GraphQLService.unvalidated[IO](TestMapping)

  private def resolver(auth: Authenticator[IO], policy: AnonymousPolicy): AuthResolver[IO] =
    given Logger[IO] = TestingLogger.impl[IO]()
    new AuthResolver(service, auth, RoutesConfig(anonymous = policy))

  private val anonymous: Authenticator[IO] =
    Authenticator[IO](_ => IO.pure(Auth.Anonymous))

  private val Secret: String = "not-a-real-token-1234"

  private val bearer: Option[Authorization] =
    Authorization(Credentials.Token(CIString("Bearer"), Secret)).some

  private def denied(message: String): Authenticator[IO] =
    Authenticator[IO](_ => IO.pure(Auth.Denied(message)))

  // Resolver with a testing logger, and an effect that reads the logged messages.
  private def logging(auth: Authenticator[IO]): IO[(AuthResolver[IO], IO[Vector[LogMessage]])] =
    IO.delay:
      val log          = TestingLogger.impl[IO]()
      given Logger[IO] = log
      (new AuthResolver(service, auth, RoutesConfig()), log.logged)

  private def warnings(ms: Vector[LogMessage]): Vector[String] =
    ms.collect { case WARN(m, _) => m }

  test("An authenticated result gives the real service and its context."):
    val ctx = RequestContext(Env("user" -> "bob"))
    resolver(Authenticator[IO](_ => IO.pure(Auth.Authenticated(ctx))), AnonymousPolicy.IntrospectionOnly)
      .resolve(None)
      .map:
        case Right((s, c)) => assert(s eq service); assertEquals(c, ctx)
        case Left(m)       => fail(s"Expected a service, got $m")

  test("An anonymous result with IntrospectionOnly gives a different service and an empty context."):
    resolver(anonymous, AnonymousPolicy.IntrospectionOnly)
      .resolve(None)
      .map:
        case Right((s, c)) => assert(!(s eq service)); assertEquals(c, RequestContext.empty)
        case Left(m)       => fail(s"Expected a service, got $m")

  test("An anonymous result with Allow gives the real service and an empty context."):
    resolver(anonymous, AnonymousPolicy.Allow)
      .resolve(None)
      .map:
        case Right((s, c)) => assert(s eq service); assertEquals(c, RequestContext.empty)
        case Left(m)       => fail(s"Expected a service, got $m")

  test("An anonymous result with Deny gives the standard message."):
    resolver(anonymous, AnonymousPolicy.Deny)
      .resolve(None)
      .assertEquals(Left("Access denied."): Either[String, (GraphQLService[IO], RequestContext)])

  test("A denied result gives its own message."):
    resolver(denied("bad token"), AnonymousPolicy.Allow)
      .resolve(None)
      .assertEquals(Left("bad token"))

  test("The introspection service is built once."):
    val r = resolver(anonymous, AnonymousPolicy.IntrospectionOnly)
    (r.resolve(None), r.resolve(None)).mapN: (a, b) =>
      assert(a.toOption.get._1 eq b.toOption.get._1)

  test("A denied result logs a warning with the reason and the scheme, but not the credentials."):
    logging(denied("Access denied: token expired")).flatMap: (r, logged) =>
      r.resolve(bearer) >> logged.map: ms =>
        val ws = warnings(ms)
        assertEquals(ws.length, 1, ms)
        assert(ws.head.contains("Access denied: token expired"), ws.head)
        assert(ws.head.contains("Bearer"), ws.head)
        assert(!ms.exists(_.message.contains(Secret)), ms)

  test("A long reason is cut, with a marker."):
    logging(denied("x" * 256 + "tail")).flatMap: (r, logged) =>
      r.resolve(bearer) >> logged.map: ms =>
        val ws = warnings(ms)
        assertEquals(ws.length, 1, ms)
        assert(ws.head.endsWith("x" * 256 + "..."), ws.head)
        assert(!ws.head.contains("tail"), ws.head)

  test("A malformed Authorization header logs a warning, and the request goes on without credentials."):
    val malformed = Headers(Header.Raw(CIString("Authorization"), s"@@@ $Secret"))
    logging(anonymous).flatMap: (r, logged) =>
      r.resolve(malformed).flatMap: res =>
        assert(res.isRight, res)
        logged.map: ms =>
          val ws = warnings(ms)
          assertEquals(ws.length, 1, ms)
          assert(ws.head.contains("malformed"), ws.head)
          assert(!ms.exists(_.message.contains(Secret)), ms)

  test("A malformed Authorization property of connection_init closes the socket with 4403 and logs a warning."):
    val props = JsonObject("Authorization" -> Json.fromString(s"@@@ $Secret"))
    TestControl.executeEmbed:
      for
        (r, logged) <- logging(anonymous)
        queue       <- Queue.unbounded[IO, Reply]
        replies     <-
          given Logger[IO] = BaseSuite.logger
          Connection[IO](r, queue).use: conn =>
            conn.receive(FromClient.ConnectionInit(props.some)) >> IO.sleep(1.second) >> queue.tryTakeN(none)
        ms          <- logged
      yield
        assert(replies.exists { case Reply.CloseWith(GraphQLWSError.Forbidden(_)) => true; case _ => false }, replies)
        val ws = warnings(ms)
        assertEquals(ws.length, 1, ms)
        assert(ws.head.contains("malformed"), ws.head)
        assert(!ms.exists(_.message.contains(Secret)), ms)

  test("A well-formed or missing Authorization header logs no warning about its form."):
    logging(anonymous).flatMap: (r, logged) =>
      r.resolve(Headers.empty) >> r.resolve(Headers(bearer.get)) >> logged.map(ms => assertEquals(warnings(ms), Vector.empty))

  test("A denied result without credentials logs a warning."):
    logging(denied("Access denied")).flatMap: (r, logged) =>
      r.resolve(None) >> logged.map(ms => assertEquals(warnings(ms).length, 1, ms))

  test("Authenticated and anonymous results log no warning."):
    val ctx = RequestContext(Env("user" -> "bob"))
    for
      (a, la) <- logging(Authenticator[IO](_ => IO.pure(Auth.Authenticated(ctx))))
      (b, lb) <- logging(anonymous)
      _       <- a.resolve(bearer)
      _       <- b.resolve(None)
      ms      <- (la, lb).mapN(_ ++ _)
    yield assertEquals(warnings(ms), Vector.empty)
