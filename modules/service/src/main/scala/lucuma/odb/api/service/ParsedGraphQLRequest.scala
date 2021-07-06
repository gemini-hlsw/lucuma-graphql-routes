// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.service

import io.circe.Json
import sangria.ast.{Document, OperationType}

final case class ParsedGraphQLRequest(
  query: Document,
  op:    Option[String],
  vars:  Option[Json]
) {

  def isSubscription: Boolean =
    query.operationType(op).contains(OperationType.Subscription)

}
