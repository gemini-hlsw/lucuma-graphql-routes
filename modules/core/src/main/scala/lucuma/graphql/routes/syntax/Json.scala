// Copyright (c) 2016-2022 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes.syntax

import cats.data.NonEmptyList
import clue.model.GraphQLCombinedResponse
import clue.model.GraphQLDataResponse
import clue.model.GraphQLError
import clue.model.StreamingMessage.FromServer
import clue.model.StreamingMessage.FromServer.Data
import clue.model.StreamingMessage.FromServer.Error
import clue.model.json._
import io.circe.Json

final class JsonOps(val self: Json) extends AnyVal {

  private def unexpectedErrorMessage(id: String): FromServer =
    Error(
      id,
      NonEmptyList.one(
        GraphQLError(s"Internal server error, expected error or data but got:\n${self.spaces2}")
      )
    )

  def toStreamingMessage(id: String): FromServer =
    self
      .as[GraphQLCombinedResponse[Json]]
      .fold(
        _ => unexpectedErrorMessage(id),
        _.toEither.fold(Error(id, _), data => Data(id, GraphQLDataResponse(data)))
      )

}

trait ToJsonOps {
  implicit def toJsonOps(j: Json): JsonOps =
    new JsonOps(j)
}

object json extends ToJsonOps
