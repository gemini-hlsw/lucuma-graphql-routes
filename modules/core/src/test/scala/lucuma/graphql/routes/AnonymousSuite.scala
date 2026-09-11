// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import cats.implicits.*
import clue.RemoteInitializationException
import grackle.Env
import grackle.Result
import io.circe.literal.*
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.client.websocket.WSFrame
import org.http4s.headers.Authorization

class AnonymousSuite extends BaseSuite:
  import BaseSuite.ClientOption.*

  val graphQLService: GraphQLService[IO] = GraphQLService.unvalidated[IO](TestMapping)

  // "bob" is a user. Any other token is a refusal. No token means anonymous.
  override def authenticator: Authenticator[IO] =
    Authenticator[IO]:
      case None                                                             => IO.pure(Auth.Anonymous)
      case Some(Authorization(Credentials.Token(AuthScheme.Bearer, "bob"))) =>
        IO.pure(Auth(Env("user" -> "bob")))
      case Some(_)                                                          => IO.pure(Auth.Denied("bad token"))

  test("[http] An anonymous client reads the schema."):
    query(None, "query { __schema { queryType { name } } }", None, Http)
      .map(j =>
        assertEquals(
          j.hcursor.downField("__schema").downField("queryType").downField("name").as[String],
          Right("Query")
        )
      )

  test("[http] An anonymous client cannot read a data field."):
    interceptGraphQL("Field 'echo' requires authentication.")(
      query(None, "query { echo(s: \"hi\") }", None, Http)
    )

  test("[http] A rejected token gives the message of the refusal."):
    interceptGraphQL("bad token")(query(Some("steve"), "query { echo(s: \"hi\") }", None, Http))

  test("[http] An authenticated client reads a data field."):
    expect(Some("bob"), "query { echo(s: \"hi\") }", Right(json"""{ "echo": "hi" }"""), None, Http)

  test("[ws] An anonymous client reads the schema."):
    query(None, "query { __schema { queryType { name } } }", None, Ws).void

  test("[ws] A rejected token closes the connection."):
    interceptIO[RemoteInitializationException](
      query(Some("steve"), "query { echo(s: \"hi\") }", None, Ws).void
    )

  test("[ws] A rejected token closes with code 4403 and the message of the refusal."):
    rawWsFrames(1)(
      WSFrame.Text("""{"type":"connection_init","payload":{"Authorization":"Bearer steve"}}""")
    )
      .map:
        case List(WSFrame.Close(code, reason)) =>
          assertEquals(code, 4403)
          // `GraphQLWSError.Forbidden` writes the reason as `s"Forbidden: $msg"`.
          assertEquals(reason, "Forbidden: bad token")
        case other                             =>
          fail(s"Expected one close frame, got $other")

  // The introspection mapping is what an anonymous client sees under `AnonymousPolicy.IntrospectionOnly`.
  // These tests exercise it directly, without going through the routes.
  private lazy val introspectionOnly: GraphQLService[IO] =
    GraphQLService.unvalidated[IO](IntrospectionMapping[IO](TestMapping.schema))

  test("The introspection mapping compiles an introspection query."):
    assert(
      introspectionOnly
        .parse(RequestContext.empty, "query { __schema { types { name } } }", None, None)
        .hasValue
    )

  test("The introspection mapping runs an introspection query."):
    introspectionOnly
      .parse(RequestContext.empty, "query { __schema { queryType { name } } }", None, None)
      .flatTraverse(op =>
        introspectionOnly.query(RequestContext.empty,
                                op,
                                "query { __schema { queryType { name } } }"
        )
      )
      .map(r => assert(r.hasValue, r.toString))

  // Compiles the document with the introspection mapping and asserts the message of the refusal.
  private def assertRejects(query: String, message: String): Unit =
    introspectionOnly.parse(RequestContext.empty, query, None, None) match
      case Result.Failure(ps) => assertEquals(ps.head.message, message)
      case other              => fail(s"Expected a failure, got $other")

  test("The introspection mapping rejects a data field."):
    assertRejects("query { echo(s: \"hi\") }", "Field 'echo' requires authentication.")

  test("The introspection mapping rejects a mutation field."):
    assertRejects("mutation { slowUpdate }", "Field 'slowUpdate' requires authentication.")

  test("The introspection mapping rejects a subscription field."):
    assertRejects("subscription { echo(s: \"hi\") }", "Field 'echo' requires authentication.")
