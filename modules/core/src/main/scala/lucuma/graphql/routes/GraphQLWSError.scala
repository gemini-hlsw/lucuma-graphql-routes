// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

enum GraphQLWSError(val code: Int, val reason: String):
  case InvalidMessage(message: String) extends GraphQLWSError(4400, s"Invalid message received: $message")// TODO Not implemented yet 
  case Unauthorized(request: String) extends GraphQLWSError(4401, s"Unauthorized. Request '$request' received on un-initialized connection.")
  case Forbidden(msg: String) extends GraphQLWSError(4403, s"Forbidden: $msg")
  case InitializationTimeout extends GraphQLWSError(4408, "Connection initialization timeout") // TODO Not implemented yet 
  case SubscriberAlreadyExists(id: String) extends GraphQLWSError(4409, s"Subscriber with id '$id' already exists") // TODO Not implemented yet 
  case TooManyInitializationRequests extends GraphQLWSError(4429, "Too many initialization requests") // TODO Not implemented yet 