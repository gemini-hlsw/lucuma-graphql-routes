// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.schema

import edu.gemini.odb.api.repo.OdbRepo
import cats.effect.Effect
import edu.gemini.odb.api.model.InputError
import sangria.execution.{ExceptionHandler, HandledException}
import sangria.schema.Schema

object ProgramSchema {

  /**
   * Handle InputError.Exception to create a separate error message for each
   * violation that was encountered.
   */
  val exceptionHandler: ExceptionHandler =
    ExceptionHandler(
      onException = {
        case (m, InputError.Exception(nec)) =>
          HandledException.multiple(nec.toNonEmptyVector.toVector.map { e =>
            (e.message, Map.empty[String, m.Node], Nil)
          })
      }
    )

  def apply[F[_]: Effect]: Schema[OdbRepo[F], Unit] =
    Schema(QueryType[F], Some(MutationType[F]))

}
