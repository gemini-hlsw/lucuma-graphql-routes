// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadThrow
import cats.syntax.all.*
import fs2.Compiler
import org.http4s.headers.Authorization
import org.typelevel.otel4s.trace.Tracer

/**
 * Turns the result of authentication into the service and the context that run the operation. The
 * HTTP routes and the web socket share one instance, so the introspection service is built once.
 */
private[routes] class AuthResolver[F[_]: {MonadThrow, Tracer}](
  service:       GraphQLService[F],
  authenticator: Authenticator[F],
  val config:    RoutesConfig
)(using Compiler[F, F]):

  /** The service that answers an anonymous client under `AnonymousPolicy.IntrospectionOnly`. */
  private lazy val introspectionService: GraphQLService[F] =
    GraphQLService.unvalidated[F](IntrospectionMapping[F](service.mapping.schema))

  /** Default message for a refusal that carries no message of its own. */
  private val AccessDenied = "Access denied."

  /**
   * @return the service and the context on the right, or the message of the refusal on the left
   */
  def resolve(auth: Option[Authorization]): F[Either[String, (GraphQLService[F], RequestContext)]] =
    authenticator.authenticate(auth).map:

      case Auth.Authenticated(ctx) =>
        (service, ctx).asRight

      case Auth.Anonymous =>
        config.anonymous match
          case AnonymousPolicy.IntrospectionOnly => (introspectionService, RequestContext.empty).asRight
          case AnonymousPolicy.Allow             => (service, RequestContext.empty).asRight
          case AnonymousPolicy.Deny              => AccessDenied.asLeft

      case Auth.Denied(message) =>
        message.asLeft
