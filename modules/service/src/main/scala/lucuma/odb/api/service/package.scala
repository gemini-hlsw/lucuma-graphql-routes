// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api

import lucuma.core.model.User
import lucuma.odb.syntax.logger._

import cats.FlatMap
import cats.syntax.all._

import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.extras.LogLevel


package object service {

  def log[F[_]: Logger](level: LogLevel, u: Option[User], s: => String): F[Unit] =
    Logger[F].log(level, s"$s: (user=${u.map(_.toString()).getOrElse("None")})")

  def log[F[_]: Logger: FlatMap](level: LogLevel, u: F[Option[User]], s: => String): F[Unit] =
    u.flatMap(log(level, _, s))

  def debug[F[_]: Logger](u: Option[User], s: => String): F[Unit] =
    log(LogLevel.Debug, u, s)

  def debug[F[_]: Logger: FlatMap](u: F[Option[User]], s: => String): F[Unit] =
    log(LogLevel.Debug, u, s)

  def info[F[_]: Logger](u: Option[User], s: => String): F[Unit] =
    log(LogLevel.Info, u, s)

  def info[F[_]: Logger: FlatMap](u: F[Option[User]], s: => String): F[Unit] =
    log(LogLevel.Info, u, s)

}
