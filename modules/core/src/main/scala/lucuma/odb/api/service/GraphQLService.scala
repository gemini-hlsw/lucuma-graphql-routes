// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.odb.api.service

import fs2.Stream
import io.circe._
import lucuma.core.model.User

trait GraphQLService[F[_]] {

  def query(request: ParsedGraphQLRequest): F[Either[Throwable, Json]]

  def subscribe(user: Option[User], request: ParsedGraphQLRequest): F[Stream[F, Either[Throwable, Json]]]

}
