// Copyright (c) 2016-2021 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import fs2.Stream
import io.circe._
import lucuma.core.model.User

trait GraphQLService[F[_]] {

  type Document

  case class ParsedGraphQLRequest(
    query: Document,
    op:    Option[String],
    vars:  Option[Json]
  ) {
    def isSubscription: Boolean = GraphQLService.this.isSubscription(this)
  }

  def parse(query: String): Either[Throwable, Document]

  protected def isSubscription(doc: ParsedGraphQLRequest): Boolean

  def query(request: ParsedGraphQLRequest): F[Either[Throwable, Json]]

  def subscribe(user: Option[User], request: ParsedGraphQLRequest): Stream[F, Either[Throwable, Json]]

  def format(err: Throwable): Json

}
