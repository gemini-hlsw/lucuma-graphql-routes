// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import cats.effect.Ref
import cats.syntax.all.*
import grackle.Env
import munit.CatsEffectSuite
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.headers.Authorization

import scala.concurrent.duration.*

class AuthCacheSuite extends CatsEffectSuite:

  private def token(s: String): Option[Authorization] =
    Authorization(Credentials.Token(AuthScheme.Bearer, s)).some

  // An authenticator that counts its calls, so a test can prove that the cache prevents one.
  private def counting(result: Auth): IO[(Ref[IO, Int], Authenticator[IO])] =
    Ref
      .of[IO, Int](0)
      .map: ref =>
        (ref, Authenticator[IO](_ => ref.update(_ + 1).as(result)))

  test("The cache calls the underlying authenticator once for two requests with the same token."):
    counting(Auth(Env("user" -> "bob"))).flatMap: (count, under) =>
      under
        .cached(1.minute)
        .flatMap: cached =>
          cached.authenticate(token("bob")) *>
            cached.authenticate(token("bob")) *>
            count.get.assertEquals(1)

  test("The cache keys on the token, so a second token is a second call."):
    counting(Auth(Env("user" -> "bob"))).flatMap: (count, under) =>
      under
        .cached(1.minute)
        .flatMap: cached =>
          cached.authenticate(token("bob")) *>
            cached.authenticate(token("sue")) *>
            count.get.assertEquals(2)

  test("An entry expires after the ttl."):
    counting(Auth(Env("user" -> "bob"))).flatMap: (count, under) =>
      under
        .cached(100.milliseconds)
        .flatMap: cached =>
          cached.authenticate(token("bob")) *>
            IO.sleep(200.milliseconds) *>
            cached.authenticate(token("bob")) *>
            count.get.assertEquals(2)

  test("A denied result is not cached by default."):
    counting(Auth.Denied("no")).flatMap: (count, under) =>
      under
        .cached(1.minute)
        .flatMap: cached =>
          cached.authenticate(token("bob")) *>
            cached.authenticate(token("bob")) *>
            count.get.assertEquals(2)

  test("cacheDenials caches a denied result."):
    counting(Auth.Denied("no")).flatMap: (count, under) =>
      AuthCache
        .timed[IO](AuthCacheConfig(1.minute, cacheDenials = true))
        .flatMap: cache =>
          val cached = under.cachedWith(cache)
          cached.authenticate(token("bob")) *>
            cached.authenticate(token("bob")) *>
            count.get.assertEquals(1)

  test("An anonymous result is cached."):
    counting(Auth.Anonymous).flatMap: (count, under) =>
      under
        .cached(1.minute)
        .flatMap: cached =>
          cached.authenticate(None) *>
            cached.authenticate(None) *>
            count.get.assertEquals(1)

  test("maxEntries evicts the oldest entries once the limit is reached."):
    counting(Auth(Env("user" -> "bob"))).flatMap: (count, under) =>
      AuthCache
        .timed[IO](AuthCacheConfig(1.minute, maxEntries = 3))
        .flatMap: cache =>
          val cached = under.cachedWith(cache)
          List("t0", "t1", "t2", "t3", "t4").traverse_(t => cached.authenticate(token(t))) *>
            // "t0" was the first entry written, so it is among the ones the limit evicted.
            count.get.assertEquals(5) *>
            cached.authenticate(token("t0")) *>
            count.get.assertEquals(6) *>
            // "t4" is the most recent entry, so it is still cached.
            cached.authenticate(token("t4")) *>
            count.get.assertEquals(6)
