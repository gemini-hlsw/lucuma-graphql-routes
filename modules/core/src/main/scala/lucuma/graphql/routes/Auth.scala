// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import grackle.Env
import org.typelevel.otel4s.Attributes

/** The outcome of authentication for one request. */
enum Auth:

  /** The client is a known user. The routes run the operation with this context. */
  case Authenticated(context: RequestContext)

  /** The client sent no credentials. `RoutesConfig.anonymous` decides what happens. */
  case Anonymous

  /** The client sent credentials that will be rejected. */
  case Denied(message: String)

object Auth:

  /** An authenticated result with the given env and span attributes. */
  def apply(env: Env, attributes: Attributes = Attributes.empty): Auth =
    Authenticated(RequestContext(env, attributes))

  /** `None` means that the client sent no usable credentials, which is `Anonymous`. */
  def fromOption(context: Option[RequestContext]): Auth =
    context.fold(Anonymous)(Authenticated(_))
