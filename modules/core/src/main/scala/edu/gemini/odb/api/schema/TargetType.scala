// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.schema

import edu.gemini.odb.api.model.{Program, Target}
import edu.gemini.odb.api.repo.OdbRepo

import cats.MonadError
import cats.effect.Effect
import cats.effect.implicits._
import cats.implicits._
import sangria.schema._
import sangria.macros.derive._

object TargetType {

  import ProgramType.ProgramIdType

  val CreateSiderealInputType: InputObjectType[Target.CreateSidereal] =
    deriveInputObjectType[Target.CreateSidereal](
      InputObjectTypeName("CreateSiderealInput"),
      InputObjectTypeDescription("Sidereal target parameters")
    )

  def apply[F[_]: Effect](implicit M: MonadError[F, Throwable]): ObjectType[OdbRepo[F], Target] =
    ObjectType(
      name     = "Target",
      fieldsFn = () => fields(

        Field(
          name        = "id",
          fieldType   = IDType,
          description = Some("Target id."),
          resolve     = _.value.id.stringValue
        ),

        Field(
          name        = "program",
          fieldType   = ProgramType[F],
          description = Some("The program associated with the target."),
          resolve     = c => {
            val pid = c.value.pid
            (c.ctx.program.select(pid).flatMap {
              case None    =>
                M.raiseError[Program](
                  new RuntimeException(s"Missing program: ${pid.stringValue}")
                )
              case Some(p) => p.pure[F]
            }).toIO.unsafeToFuture
          }
        ),

        Field(
          name        = "name",
          fieldType   = StringType,
          description = Some("Target name."),
          resolve     = _.value.target.name
        ),

        Field(
          name        = "tracking",
          fieldType   = TrackingType[F],
          description = Some("Information required to find a target in the sky."),
          resolve     = _.value.target.track
        )

      )
    )

}
