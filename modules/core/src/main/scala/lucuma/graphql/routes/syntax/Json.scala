// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes.syntax

import clue.model.StreamingMessage.FromServer
import clue.model.StreamingMessage.FromServer.Data
import clue.model.StreamingMessage.FromServer.DataWrapper
import clue.model.StreamingMessage.FromServer.Error
import io.circe.Json

final class JsonOps(val self: Json) extends AnyVal {

  def objectField(n: String): Option[Json] =
    self.hcursor.downField(n).focus

  def errorsField: Option[Json] =
    objectField("errors").filterNot(_.isNull)

  def dataField: Option[Json] =
    objectField("data").filterNot(_.isNull)

  private def unexpectedErrorMessage(id: String): FromServer =
    Error(
      id,
      Json.arr(
        Json.obj(
          "message" -> Json.fromString(s"Internal server error, expected error or data but got:\n${self.spaces2}")
        )
      )
    )

  def toStreamingMessage(id: String): FromServer =
    dataField.fold(errorsField.fold(unexpectedErrorMessage(id))(Error(id, _))) { d =>
      Data(id, DataWrapper(d, errorsField))
    }

}

trait ToJsonOps {
  implicit def toJsonOps(j: Json): JsonOps =
    new JsonOps(j)
}

object json extends ToJsonOps
