// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadThrow
import cats.data.Ior
import cats.data.NonEmptyChain
import cats.data.NonEmptyList
import cats.syntax.all.*
import clue.model.*
import clue.model.GraphQLError
import clue.model.StreamingMessage.FromServer
import grackle.Problem
import grackle.Result
import grackle.Result.*
import io.circe.Json
import org.typelevel.otel4s.trace.Tracer

// Extract W3C trace context headers (traceparent, tracestate) from the GraphQL
// `extensions` object so otel4s can re-parent spans on the client.
extension (extensions: Option[GraphQLExtensions])

  private[routes] def traceCarrier: Map[String, String] =
    extensions.fold(Map.empty):
      _.toIterable.flatMap((k, v) => v.asString.filter(_.nonEmpty).map(k -> _)).toMap

// Only join a remote context when the client sent one. Otherwise keep the current span context.
private[routes] def joinRemote[F[_]: {Tracer as T}, A](extensions: Option[GraphQLExtensions])(fa: F[A]): F[A] =
  val carrier = extensions.traceCarrier
  if carrier.contains("traceparent") then T.joinOrRoot(carrier)(fa) else fa

def mkGraphqlError(p: Problem): GraphQLError =
  GraphQLError(
    p.message,
    NonEmptyList.fromList(p.path.map(GraphQLError.PathElement.string)),
    NonEmptyList.fromList(p.locations.map(GraphQLError.Location(_, _))),
    p.extensions
  )

def mkGraphqlErrors(problems: NonEmptyChain[Problem]): GraphQLErrors =
  problems.toNonEmptyList.map(mkGraphqlError)

def mkGraphqlErrors(error: Throwable): GraphQLErrors =
  NonEmptyList.one(mkGraphqlError(Problem(s"Internal Error: ${error.getMessage}")))

def mkFromServer[F[_]: MonadThrow](r: Result[Json], id: String): F[Either[FromServer.Error, FromServer.Next]] =
  r match {
    case Success(json)      => FromServer.Next(id, GraphQLResponse(json.rightIor)).asRight.pure[F]
    case Warning(ps, json)  => FromServer.Next(id, GraphQLResponse(Ior.both(mkGraphqlErrors(ps), json))).asRight.pure[F]
    case Failure(ps)        => FromServer.Error(id, mkGraphqlErrors(ps)).asLeft.pure[F]
    case InternalError(err) => FromServer.Error(id, mkGraphqlErrors(err)).asLeft.pure[F]
  }
