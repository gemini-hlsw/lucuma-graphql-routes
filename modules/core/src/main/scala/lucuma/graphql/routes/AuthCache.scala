// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.Clock
import cats.effect.Ref
import cats.effect.Temporal
import cats.syntax.all.*
import org.http4s.headers.Authorization

import scala.concurrent.duration.FiniteDuration

/**
 * @param ttl
 *   the life of every entry, counted from the moment the cache stores it
 * @param cacheDenials
 *   whether the cache stores an `Auth.Denied` result. It is `false` by default, so a client that
 *   repairs a revoked token is not locked out until the entry expires.
 * @param maxEntries
 *   the most entries the cache holds at once.
 */
final case class AuthCacheConfig(
  ttl:          FiniteDuration,
  cacheDenials: Boolean = false,
  maxEntries:   Int = 1024
)

/** A store of authentication results, keyed on the credentials of the request. */
trait AuthCache[F[_]]:
  def get(key: Option[Authorization]): F[Option[Auth]]
  def put(key: Option[Authorization], value: Auth): F[Unit]

object AuthCache:

  private type Entry = (auth: Auth, expiry: FiniteDuration)

  /**
   * A cache that holds every entry for `config.ttl`, up to `config.maxEntries` entries. A read
   * filters out an entry that expired, but does not drop it. A write drops the entries that
   * expired only when the map is full, so the map does not grow past the limit.
   */
  def timed[F[_]: Temporal](config: AuthCacheConfig): F[AuthCache[F]] =
    Ref
      .of[F, Map[Option[Authorization], Entry]](Map.empty)
      .map: ref =>
        new AuthCache[F]:

          def get(key: Option[Authorization]): F[Option[Auth]] =
            (Clock[F].monotonic, ref.get).mapN: (now, entries) =>
              entries.get(key).collect { case (auth, expiry) if expiry > now => auth }

          // Makes room for one more entry. A map below the limit passes through untouched, which
          // keeps the common write cheap. A full map drops every entry that expired, then the
          // entries that expire soonest, until it holds fewer than `maxEntries`.
          def evictToLimit(
            entries: Map[Option[Authorization], Entry],
            now:     FiniteDuration
          ): Map[Option[Authorization], Entry] =
            if entries.size < config.maxEntries then entries
            else
              val live   = entries.filter(_._2.expiry > now)
              val overBy = live.size - config.maxEntries + 1
              if overBy <= 0 then live
              else live -- live.toList.sortBy(_._2.expiry).take(overBy).map(_._1)

          def put(key: Option[Authorization], value: Auth): F[Unit] =
            val store = value match
              case Auth.Denied(_) => config.cacheDenials
              case _              => true
            Clock[F].monotonic.flatMap: now =>
              ref
                .update { entries =>
                  evictToLimit(entries, now) + (key -> (value, now + config.ttl))
                }
                .whenA(store)
