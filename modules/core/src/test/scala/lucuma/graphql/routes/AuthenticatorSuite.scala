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

  test("Auth.apply builds an authenticated result, with empty attributes by default."):
    val as = Attributes(Attribute("user.name", "bob"))
    assertEquals(Auth(Env("user" -> "bob")), Auth.Authenticated(RequestContext(Env("user" -> "bob"), Attributes.empty)))
    assertEquals(Auth(Env("user" -> "bob"), as), Auth.Authenticated(RequestContext(Env("user" -> "bob"), as)))

  test("Auth.fromOption maps None to Anonymous and Some to Authenticated."):
    val ctx = RequestContext(Env("user" -> "bob"))
    assertEquals(Auth.fromOption(None), Auth.Anonymous)
    assertEquals(Auth.fromOption(Some(ctx)), Auth.Authenticated(ctx))

  private val bob: Option[Authorization] =
    Authorization(Credentials.Token(AuthScheme.Bearer, "bob")).some

  test("Authenticator.open authenticates every request with an empty context."):
    Authenticator.open[IO].authenticate(None).assertEquals(Auth.Authenticated(RequestContext.empty))

  test("fromOptionF maps None to Anonymous and Some to Authenticated."):
    val a = Authenticator.fromOptionF[IO](h => IO.pure(h.as(RequestContext(Env("user" -> "bob")))))
    a.authenticate(None).assertEquals(Auth.Anonymous) *>
      a.authenticate(bob).assertEquals(Auth.Authenticated(RequestContext(Env("user" -> "bob"))))
