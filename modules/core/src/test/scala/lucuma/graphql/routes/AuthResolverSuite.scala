// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import cats.syntax.all.*
import grackle.Env
import munit.CatsEffectSuite
import org.typelevel.otel4s.trace.Tracer

class AuthResolverSuite extends CatsEffectSuite:

  given Tracer[IO] = Tracer.noop[IO]

  private lazy val service: GraphQLService[IO] =
    GraphQLService.unvalidated[IO](TestMapping)

  private def resolver(auth: Authenticator[IO], policy: AnonymousPolicy): AuthResolver[IO] =
    AuthResolver(service, auth, RoutesConfig(anonymous = policy))

  private val anonymous: Authenticator[IO] =
    Authenticator[IO](_ => IO.pure(Auth.Anonymous))

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
    resolver(Authenticator[IO](_ => IO.pure(Auth.Denied("bad token"))), AnonymousPolicy.Allow)
      .resolve(None)
      .assertEquals(Left("bad token"))

  test("The introspection service is built once."):
    val r = resolver(anonymous, AnonymousPolicy.IntrospectionOnly)
    (r.resolve(None), r.resolve(None)).mapN: (a, b) =>
      assert(a.toOption.get._1 eq b.toOption.get._1)
