// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes.syntax

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.extras.LogLevel

final class LoggerOps[F[_]](val self: Logger[F]) extends AnyVal {

  def log(level: LogLevel, message: => String): F[Unit] =
    level match {
      case LogLevel.Error => self.error(message)
      case LogLevel.Warn  => self.warn(message)
      case LogLevel.Info  => self.info(message)
      case LogLevel.Debug => self.debug(message)
      case LogLevel.Trace => self.trace(message)
    }

  def log(t: Throwable)(level: LogLevel, message: => String): F[Unit] =
    level match {
      case LogLevel.Error => self.error(t)(message)
      case LogLevel.Warn  => self.warn(t)(message)
      case LogLevel.Info  => self.info(t)(message)
      case LogLevel.Debug => self.debug(t)(message)
      case LogLevel.Trace => self.trace(t)(message)
    }

}

trait ToLoggerOps {
  implicit def ToLoggerOps[F[_]](log: Logger[F]): LoggerOps[F] =
    new LoggerOps[F](log)
}

object logger extends ToLoggerOps
