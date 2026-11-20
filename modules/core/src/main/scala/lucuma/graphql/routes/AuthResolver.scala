// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadThrow
import cats.effect.Clock
import cats.syntax.all.*
import fs2.Compiler
import org.http4s.Header
import org.http4s.Headers
import org.http4s.headers.Authorization
import org.typelevel.log4cats.Logger
import org.typelevel.otel4s.trace.Tracer

/**
 * Turns the result of authentication into the service and the context that run the operation. The
 * HTTP routes and the web socket share one instance, so the introspection service is built once.
 */
private[routes] class AuthResolver[F[_]: {MonadThrow, Logger, Clock, Tracer}](
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
   * Resolves the `Authorization` header of an HTTP request. Headers that can't be parsed are logged.
   */
  def resolve(headers: Headers): F[Either[String, (GraphQLService[F], RequestContext)]] =
    val auth = headers.get[Authorization]
    Logger[F]
      .warn("Authorization header is malformed, continuing with no credentials")
      .whenA(auth.isEmpty && headers.get(Header[Authorization].name).isDefined) >>
      resolve(auth)

  /**
   * @return the service and the context on the right, or the message of the refusal on the left
   */
  def resolve(auth: Option[Authorization]): F[Either[String, (GraphQLService[F], RequestContext)]] =
    authenticator
      .authenticate(auth)
      .flatTap:
        case Auth.Denied(message) => logDenial(auth.fold("no credentials")(a => s"scheme ${a.credentials.authScheme}"), message)
        case _                    => MonadThrow[F].unit
      .map:

        case Auth.Authenticated(ctx) =>
          (service, ctx).asRight

        case Auth.Anonymous =>
          config.anonymous match
            case AnonymousPolicy.IntrospectionOnly => (introspectionService, RequestContext.empty).asRight
            case AnonymousPolicy.Allow             => (service, RequestContext.empty).asRight
            case AnonymousPolicy.Deny              => AccessDenied.asLeft

        case Auth.Denied(message) =>
          message.asLeft

  /** Logs credentials that do not parse, such as a malformed `connection_init` property. */
  def logMalformed(reason: String): F[Unit] =
    logDenial("malformed credentials", reason)

  // Logs the credential type and the reason, never the credentials. The reason is truncated.
  private def logDenial(credentials: String, message: String): F[Unit] =
    val max    = 256
    val reason = if message.length > max then message.take(max) + "..." else message
    Logger[F].warn(s"Authentication denied ($credentials): $reason")
