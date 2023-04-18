// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.data.NonEmptyList
import cats.syntax.option._
import clue.model.GraphQLError
import clue.model.GraphQLErrors
import sangria.execution.WithViolations
import sangria.parser.SyntaxError
import sangria.validation.AstNodeViolation

private[routes] object ErrorConverter {

  def toGraphQLError(t: Throwable): GraphQLErrors =
    t match {
      case e: SyntaxError    => NonEmptyList.one(formatSyntaxError(e))
      case e: WithViolations => formatWithViolations(e)
      case _                 => NonEmptyList.one(formatThrowable(t))
    }

  private def formatSyntaxError(e: SyntaxError): GraphQLError =
    GraphQLError(
      e.getMessage,
      locations = NonEmptyList.one(GraphQLError.Location(e.originalError.position.line, e.originalError.position.column)).some
    )

  private def formatWithViolations(e: WithViolations): GraphQLErrors =
    NonEmptyList.fromList(
      e.violations.map {
        case v: AstNodeViolation =>
          GraphQLError(
            v.errorMessage,
            locations = NonEmptyList.fromList(v.locations.map(loc => GraphQLError.Location(loc.line, loc.column)))
          )
        case v                    =>
          GraphQLError(v.errorMessage)
      }.toList
    ).getOrElse(NonEmptyList.one(GraphQLError("An unspecified error has occurred.")))

  private def formatThrowable(e: Throwable): GraphQLError =
    GraphQLError(e.getMessage)

  object syntax {

    final class ErrorConverterOps(val self: Throwable) extends AnyVal {

      def toGraphQLError: GraphQLErrors =
        ErrorConverter.toGraphQLError(self)

    }

    implicit def ToErrorConverter(t: Throwable): ErrorConverterOps =
      new ErrorConverterOps(t)
  }

}
