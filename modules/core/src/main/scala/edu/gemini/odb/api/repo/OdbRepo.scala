// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.repo

import cats.implicits._
import cats.effect.Sync
import cats.effect.concurrent.Ref

trait OdbRepo[F[_]] {

  def program: ProgramRepo[F]

  def target: TargetRepo[F]

}

object OdbRepo {

  def create[F[_]: Sync]: F[OdbRepo[F]] =
    Ref.of[F, Tables](Tables.empty).map { r =>
      new OdbRepo[F] {
        override def program: ProgramRepo[F] =
          ProgramRepo.create(r)

        override def target: TargetRepo[F] =
          TargetRepo.create(r)
      }
    }

}