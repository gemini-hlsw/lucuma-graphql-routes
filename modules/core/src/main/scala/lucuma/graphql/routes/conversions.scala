// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.data.NonEmptyList
import clue.model.GraphQLError
import edu.gemini.grackle.Problem

object conversions {

  final class ProblemOps(val problem: Problem) extends AnyVal {

    def toGraphQLError: GraphQLError = 
      GraphQLError(
        problem.message,
        NonEmptyList.fromList(problem.path.map(GraphQLError.PathElement.string)),
        NonEmptyList.fromList(problem.locations.map{ case(line, col) => GraphQLError.Location(line, col) })
      )

  }

  implicit def ToProblemOps(problem: Problem): ProblemOps =
    new ProblemOps(problem)

}
