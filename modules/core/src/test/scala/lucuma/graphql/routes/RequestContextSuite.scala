// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import fs2.Stream
import grackle.Env
import grackle.QueryCompiler.Elab
import grackle.QueryCompiler.SelectElaborator
import grackle.Result
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json
import io.circe.literal.*
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.headers.Authorization

import BaseSuite.ClientOption.*

// A mapping that answers with the user of the request context. The elaborator reads the env that
// `Routes` passes to `compile`, and the subscription reads the env of the root cursor.
object ContextMapping extends CirceMapping[IO]:
  val schema                    = schema"""
    type Query { whoAmI: String! }
    type Subscription { whoAmI: String! }
  """
  val QueryType                 = schema.ref("Query")
  val SubscriptionType          = schema.ref("Subscription")
  val typeMappings              = TypeMappings.unchecked(
    ObjectMapping(QueryType,
                  List(
                    CursorFieldJson("whoAmI", c => c.envR[String]("user").map(Json.fromString), Nil)
                  )
    ),
    ObjectMapping(SubscriptionType,
                  List(
                    RootStream.computeJson("whoAmI"): (_, e) =>
                      Stream.emit(e.getR[String]("user").map(Json.fromString)).covary[IO]
                  )
    )
  )
  override val selectElaborator = SelectElaborator:
    case (QueryType | SubscriptionType, "whoAmI", Nil) =>
      // Proves that the env of `compile` reaches elaboration: the value moves from the root env
      // into the local env of the field, where the cursor reads it.
      Elab
        .env[String]("user")
        .flatMap:
          case Some(u) => Elab.env("user" -> u)
          case None    => Elab.failure("No user in the context.")

class RequestContextSuite extends BaseSuite:

  val graphQLService: GraphQLService[IO] =
    GraphQLService.unvalidated(ContextMapping)

  override def authenticator: Authenticator[IO] =
    Authenticator[IO]:
      case Some(Authorization(Credentials.Token(AuthScheme.Bearer, t))) =>
        IO.pure(Auth(Env("user" -> t)))
      case _                                                            => IO.pure(Auth.Denied("Access denied."))

  test("[http] The context of the request reaches the elaborator."):
    expect(Some("bob"), "query { whoAmI }", Right(json"""{ "whoAmI": "bob" }"""), None, Http)

  test("[http] A second request with a second token gets its own context."):
    expect(Some("sue"), "query { whoAmI }", Right(json"""{ "whoAmI": "sue" }"""), None, Http)

  test("[ws] The context of the socket reaches a subscription."):
    subscriptionExpect(
      bearerToken = Some("bob"),
      query = "subscription { whoAmI }",
      mutations = Right(IO.unit),
      expected = List(json"""{ "whoAmI": "bob" }"""),
      variables = None
    )
