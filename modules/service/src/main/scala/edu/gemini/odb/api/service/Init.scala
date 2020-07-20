// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.service

import cats.effect.Sync
import edu.gemini.odb.api.repo.OdbRepo
import cats.implicits._
import edu.gemini.odb.api.model.{Program, Target}

object Init {
  def initialize[F[_]: Sync]: OdbRepo[F] => F[Unit] = { repo =>
    for {
      p <- repo.program.insert(
             Program.Create(
               Some("Observing Stars in Constellation Orion for No Particular Reason")
             )
           )
      _ <- repo.target.insertSidereal(
             Target.CreateSidereal(
               p.id,
               "Betelgeuse",
               "05:55:10.305",
               "07:24:25.43"
             )
           )
      _ <- repo.target.insertSidereal(
             Target.CreateSidereal(
               p.id,
               "Rigel",
               "05:14:32.272",
               "-08:12:05.90"
             )
           )
    } yield ()
  }

}
