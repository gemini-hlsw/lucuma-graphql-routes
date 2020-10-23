// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.service.syntax

import clue.model.StreamingMessage.FromServer.{Data, DataWrapper}
import io.circe.Json

final class JsonOps(val self: Json) extends AnyVal {

  def objectField(n: String): Option[Json] =
    self.hcursor.downField(n).focus

  def toDataMessage(id: String): Data =
    Data(
      id,
      DataWrapper(objectField("data").getOrElse(Json.Null), objectField("errors"))
    )

}

trait ToJsonOps {
  implicit def toJsonOps(j: Json): JsonOps =
    new JsonOps(j)
}

object json extends ToJsonOps
