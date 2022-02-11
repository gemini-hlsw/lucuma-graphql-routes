// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql

import lucuma.graphql.routes.syntax.logger._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.extras.LogLevel

package object routes {

  def log[F[_]: Logger](level: LogLevel, s: => String): F[Unit] =
    Logger[F].log(level, s)

  def debug[F[_]: Logger](s: => String): F[Unit] =
    log(LogLevel.Debug, s)

  def info[F[_]: Logger](s: => String): F[Unit] =
    log(LogLevel.Info, s)

}
