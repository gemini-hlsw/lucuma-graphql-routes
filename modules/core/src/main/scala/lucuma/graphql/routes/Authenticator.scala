// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.Applicative
import cats.Functor
import cats.Monad
import cats.data.OptionT
import cats.effect.Temporal
import cats.syntax.all.*
import org.http4s.headers.Authorization

import scala.concurrent.duration.FiniteDuration

/**
 * Turns the `Authorization` header of a request, or the `Authorization` property of a
 * `connection_init` message, into an `Auth` result. One instance serves the whole server.
 */
trait Authenticator[F[_]]:

  def authenticate(auth: Option[Authorization]): F[Auth]

  /** This authenticator with a cache of its results. */
  def cached(ttl: FiniteDuration)(using Temporal[F]): F[Authenticator[F]] =
    AuthCache.timed[F](AuthCacheConfig(ttl)).map(cachedWith)

  /** This authenticator with a provided cache. */
  def cachedWith(cache: AuthCache[F])(using Monad[F]): Authenticator[F] =
    Authenticator: h =>
      OptionT(cache.get(h)).getOrElseF(authenticate(h).flatTap(cache.put(h, _)))

object Authenticator:

  def apply[F[_]](f: Option[Authorization] => F[Auth]): Authenticator[F] =
    new Authenticator[F]:
      def authenticate(auth: Option[Authorization]): F[Auth] = f(auth)

  /**
   * Builds an authenticator from a context lookup.
   */
  def fromOptionF[F[_]: Functor](
    f: Option[Authorization] => F[Option[RequestContext]]
  ): Authenticator[F] =
    apply(h => f(h).map(Auth.fromOption))

  /**
   * Authenticates all clients with empty context.
   */
  def open[F[_]: Applicative]: Authenticator[F] =
    apply(_ => Auth.Authenticated(RequestContext.empty).pure[F])
