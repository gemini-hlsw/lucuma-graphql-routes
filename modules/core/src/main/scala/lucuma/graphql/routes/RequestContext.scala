// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import grackle.Env
import org.typelevel.otel4s.Attributes

/**
 * Everything the routes know about one request, or about one web socket.
 *
 * @param env
 *   the env added to the Grackle elaborators
 * @param attributes
 *   the attributes added to the OTEL span of every operation of the request
 */
final case class RequestContext(env: Env, attributes: Attributes = Attributes.empty)

object RequestContext:

  val empty: RequestContext =
    RequestContext(Env.empty, Attributes.empty)
