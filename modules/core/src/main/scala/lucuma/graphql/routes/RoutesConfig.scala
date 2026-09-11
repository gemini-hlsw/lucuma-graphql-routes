// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import grackle.QueryCompiler.IntrospectionLevel

import scala.concurrent.duration.FiniteDuration

/** What the routes do with a client that sends no credentials. */
enum AnonymousPolicy:

  /** The client can read the schema and nothing else. This is the default. */
  case IntrospectionOnly

  /** The client gets the full service with an empty context. */
  case Allow

  /** The client gets status 403, or close code 4403. */
  case Deny

/**
 * The paths and the policies of one set of routes.
 */
final case class RoutesConfig(
  graphQLPath:    String = "graphql",
  wsPath:         String = "ws",
  playgroundPath: String = "playground.html",
  keepAlive:      FiniteDuration = WsRouteHandler.DefaultKeepAlive,
  anonymous:      AnonymousPolicy = AnonymousPolicy.IntrospectionOnly,
  introspection:  IntrospectionLevel = IntrospectionLevel.Full
)

object RoutesConfig:
  val Default: RoutesConfig = RoutesConfig()
