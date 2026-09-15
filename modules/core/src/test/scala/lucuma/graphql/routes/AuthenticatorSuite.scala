// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import cats.syntax.all.*
import grackle.Env
import munit.CatsEffectSuite
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.headers.Authorization
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Attributes

class AuthenticatorSuite extends CatsEffectSuite:

  test("Auth.apply builds an authenticated result with no attributes."):
    assertEquals(Auth(Env("user" -> "bob")), Auth.Authenticated(RequestContext(Env("user" -> "bob"), Attributes.empty)))

  test("Auth.apply keeps the attributes it gets."):
    val as = Attributes(Attribute("user.name", "bob"))
    assertEquals(Auth(Env("user" -> "bob"), as).asInstanceOf[Auth.Authenticated].context.attributes, as)

  test("Auth.fromOption maps None to Anonymous."):
    assertEquals(Auth.fromOption(None), Auth.Anonymous)

  test("Auth.fromOption maps Some to Authenticated."):
    val ctx = RequestContext(Env("user" -> "bob"))
    assertEquals(Auth.fromOption(Some(ctx)), Auth.Authenticated(ctx))

  test("RequestContext.empty holds an empty env and no attributes."):
    assertEquals(RequestContext.empty, RequestContext(Env.empty, Attributes.empty))

  private val bob: Option[Authorization] =
    Authorization(Credentials.Token(AuthScheme.Bearer, "bob")).some

  test("Authenticator.open authenticates every request with an empty context."):
    Authenticator.open[IO].authenticate(None).assertEquals(Auth.Authenticated(RequestContext.empty))

  test("Authenticator.apply builds an authenticator from a function."):
    Authenticator[IO](_ => IO.pure(Auth.Denied("no entry")))
      .authenticate(bob)
      .assertEquals(Auth.Denied("no entry"))

  test("fromOptionF maps None to Anonymous and Some to Authenticated."):
    val a = Authenticator.fromOptionF[IO](h => IO.pure(h.as(RequestContext(Env("user" -> "bob")))))
    a.authenticate(None).assertEquals(Auth.Anonymous) *>
      a.authenticate(bob).assertEquals(Auth.Authenticated(RequestContext(Env("user" -> "bob"))))
