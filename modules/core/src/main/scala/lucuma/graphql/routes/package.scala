// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql

import cats.MonadThrow
import cats.data.NonEmptyChain
import cats.data.NonEmptyList
import cats.syntax.all._
import clue.model.GraphQLError
import clue.model.StreamingMessage.FromServer
import clue.model.*
import grackle.Problem
import grackle.Result
import grackle.Result.*
import io.circe.Json

package object routes {

  def mkGraphqlError(p: Problem): GraphQLError =
    GraphQLError(
      p.message,
      NonEmptyList.fromList(p.path.map(GraphQLError.PathElement.string)),
      NonEmptyList.fromList(p.locations.map { case (x, y) => GraphQLError.Location(x, y) }),
      p.extensions.map { obj => obj.toMap },
    )

  def mkGraphqlErrors(problems: NonEmptyChain[Problem]): GraphQLErrors =
    problems.toNonEmptyList.map(mkGraphqlError)

  def mkFromServer[F[_]: MonadThrow](r: Result[Json], id: String): F[Either[FromServer.Error, FromServer.Data]] =
    r match {
      case Success(json)      => FromServer.Data(id, GraphQLDataResponse(json)).asRight.pure[F]
      case Warning(ps, json)  => FromServer.Data(id, GraphQLDataResponse(json, mkGraphqlErrors(ps).some)).asRight.pure[F]
      case Failure(ps)        => FromServer.Error(id, mkGraphqlErrors(ps)).asLeft.pure[F]
      case InternalError(err) => MonadThrow[F].raiseError(err)
    }

}
