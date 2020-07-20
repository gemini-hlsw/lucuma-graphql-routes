// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.schema

import edu.gemini.odb.api.model.Program
import edu.gemini.odb.api.repo.OdbRepo
import cats.effect.Effect
import cats.effect.implicits._
import sangria.schema._
import sangria.validation.ValueCoercionViolation

object ProgramType {

  implicit val ProgramIdType: ScalarAlias[Program.Id, String] =
    ScalarAlias[Program.Id, String](
      aliasFor   = sangria.schema.StringType,
      toScalar   = _.stringValue,
      fromScalar = s => Program.Id.stringFormat.getOption(s).toRight(
        new ValueCoercionViolation(s"Expected a program id of format ${Program.Id.validFormat}") {}
      )
    )

  def apply[F[_]: Effect]: ObjectType[OdbRepo[F], Program] =
    ObjectType(
      name     = "Program",
      fieldsFn = () => fields(

        Field(
          name        = "id",
          fieldType   = IDType,
          description = Some("Program id."),
          resolve     = _.value.id.stringValue
        ),

        Field(
          name        = "name",
          fieldType   = OptionType(StringType),
          description = Some("Program name."),
          resolve     = _.value.name
        ),

        Field(
          name        = "targets",
          fieldType   = ListType(TargetType[F]),
          description = Some("All targets associated with the program (needs pagination)"),
          resolve     = c => c.ctx.target.selectAllForProgram(c.value.id).toIO.unsafeToFuture
        )

      )
    )
}
