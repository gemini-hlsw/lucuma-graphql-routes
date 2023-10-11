// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import clue.model.GraphQLErrors
import edu.gemini.grackle.Operation
import fs2.Stream
import io.circe._

trait GraphQLService[F[_]] {

  def parse(query: String, op: Option[String], vars: Option[JsonObject]): F[Either[Throwable, Operation]]

  def isSubscription(doc: Operation): Boolean

  def query(request: Operation): F[Either[Throwable, Json]]

  def subscribe(request: Operation): Stream[F, Either[Throwable, Json]]

  def format(err: Throwable): F[GraphQLErrors]

}
