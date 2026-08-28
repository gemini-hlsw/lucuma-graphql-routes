// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import clue.model.StreamingMessage.FromServer

/**
 * An item on the reply queue of a connection. `CloseWith` and `End` are terminal: the reply
 * stream ends after either of them, which closes the socket and stops the keepalive stream.
 */
enum Reply:

  /** A GraphQL message for the client. */
  case Send(message: FromServer)

  /** A close frame that carries an error code, and the last reply of the connection. */
  case CloseWith(error: GraphQLWSError)

  /** The last reply of the connection, without a close frame. */
  case End

  /** True for a reply that ends the reply stream. */
  def isTerminal: Boolean = this match
    case Send(_)      => false
    case CloseWith(_) => true
    case End          => true
