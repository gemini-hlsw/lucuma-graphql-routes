// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.schema

import edu.gemini.odb.api.model.Target
import edu.gemini.odb.api.repo.OdbRepo
import cats.effect.Effect
import cats.effect.implicits._
import sangria.schema._
import sangria.marshalling.circe._

object MutationType {

  val CreateSiderealArg: Argument[Target.CreateSidereal] =
    Argument(
      name         = "input",
      argumentType = TargetType.CreateSiderealInputType,
      description  = "Sidereal target description"
    )

  def apply[F[_]: Effect]: ObjectType[OdbRepo[F], Unit] =
    ObjectType(
      name   = "Mutation",
      fields = fields(

        Field(
          name      = "createSiderealTarget",
          fieldType = OptionType(TargetType[F]),
          arguments = List(CreateSiderealArg),
          resolve   = c =>
            c.ctx.target.insertSidereal(
              c.args.arg[Target.CreateSidereal]("input")
            ).toIO.unsafeToFuture
        )

      )
    )

}
