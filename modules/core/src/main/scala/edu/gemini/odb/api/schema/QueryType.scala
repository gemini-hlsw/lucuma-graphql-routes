// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.schema

import edu.gemini.odb.api.repo.OdbRepo
import cats.effect.Effect
import cats.effect.implicits._
import edu.gemini.odb.api.model.Program
import sangria.schema._

object QueryType {

  val ProgramId: Argument[Program.Id] =
    Argument(
      name         = "id",
      argumentType = ProgramType.ProgramIdType,
      description  = "Program ID"
    )

  def apply[F[_]: Effect]: ObjectType[OdbRepo[F], Unit] =
    ObjectType(
      name   = "Query",
      fields = fields(

        Field(
          name        = "programs",
          fieldType   = ListType(ProgramType[F]),
          description = Some("Returns all programs (needs pagination)."),
          resolve     = _.ctx.program.selectAll.toIO.unsafeToFuture
        ),

        Field(
          name        = "program",
          fieldType   = OptionType(ProgramType[F]),
          description = Some("Returns the program with the given id, if any."),
          arguments   = List(ProgramId),
          resolve     = c => c.ctx.program.select(c.arg(ProgramId)).toIO.unsafeToFuture
        )
      )

    )

  def schema[F[_]: Effect]: Schema[OdbRepo[F], Unit] =
    Schema(QueryType[F])
}
