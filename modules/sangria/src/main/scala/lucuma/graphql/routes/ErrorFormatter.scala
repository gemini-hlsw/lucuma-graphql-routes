// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import io.circe.Json
import sangria.execution.WithViolations
import sangria.parser.SyntaxError
import sangria.validation.AstNodeViolation

private[routes] object ErrorFormatter {

  def format(t: Throwable): Json =
    t match {
      case e: SyntaxError    => formatSyntaxError(e)
      case e: WithViolations => formatWithViolations(e)
      case _                 => formatThrowable(t)
    }

  private def formatSyntaxError(e: SyntaxError): Json =
    Json.obj(
      "errors" -> Json.arr(
        Json.obj(
          "message"   -> Json.fromString(e.getMessage),
          "locations" -> Json.arr(
            Json.obj(
              "line"   -> Json.fromInt(e.originalError.position.line),
              "column" -> Json.fromInt(e.originalError.position.column)
            )
          )
        )
      )
    )

  private def formatWithViolations(e: WithViolations): Json =
    Json.obj(
      "errors" -> Json.fromValues(e.violations.map {
        case v: AstNodeViolation =>
          Json.obj(
            "message"   -> Json.fromString(v.errorMessage),
            "locations" -> Json.fromValues(v.locations.map(loc =>
              Json.obj(
                "line" -> Json.fromInt(loc.line),
                "column" -> Json.fromInt(loc.column)
              )
            ))
          )
      case v                    =>
        Json.obj(
          "message" -> Json.fromString(v.errorMessage)
        )
    }))

  private def formatThrowable(e: Throwable): Json =
    Json.obj(
      "errors" -> Json.arr(
        Json.obj(
          "class"   -> Json.fromString(e.getClass.getName),
          "message" -> Json.fromString(e.getMessage)
        )
      )
    )

  object syntax {

    final class FormatOps(val self: Throwable) extends AnyVal {

      def format: Json =
        ErrorFormatter.format(self)

    }

    implicit def ToFormatOps(t: Throwable): FormatOps =
      new FormatOps(t)
  }

}
