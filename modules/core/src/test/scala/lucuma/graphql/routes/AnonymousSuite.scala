// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import grackle.Env
import grackle.Result
import io.circe.literal.*
import org.http4s.AuthScheme
import org.http4s.Credentials
import org.http4s.Method
import org.http4s.Request
import org.http4s.Status
import org.http4s.circe.*
import org.http4s.client.websocket.WSFrame
import org.http4s.headers.Authorization

// What the routes do with a client that sends no credentials, a rejected token, or an accepted
// token. `RequestContextSuite` covers the context of an accepted client.
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

  // --- an anonymous client ------------------------------------------------------

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

  test("[ws] An anonymous client reads the schema."):
    query(None, "query { __schema { queryType { name } } }", None, Ws).void

  // --- a rejected token ---------------------------------------------------------

  test("[http] A rejected token gives the message of the refusal."):
    interceptGraphQL("bad token")(query(Some("steve"), "query { echo(s: \"hi\") }", None, Http))

  // The 403 response carries a well-formed GraphQL response with the GraphQL media type, so the
  // client reads the body and reports the errors in it.
  test("[http] A rejected token gives 403 with the GraphQL media type and an errors body."):
    jsonResponse: uri =>
      Request[IO](Method.POST, uri)
        .withEntity(json"""{"query": "query { echo(s: \"hi\") }"}""")
        .putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, "steve")))
    .map: (status, headers, body) =>
      assertEquals(status, Status.Forbidden)
      assertErrorBody(headers, body)
      assertEquals(errorsOf(body).size, 1, body.spaces2)

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

  // --- an accepted token --------------------------------------------------------

  test("[ws, one-off] An accepted token runs a data query."):
    expect(Some("bob"), "query { echo(s: \"hi\") }", Right(json"""{ "echo": "hi" }"""), None, Ws)

  // --- the introspection mapping ------------------------------------------------

  // The introspection mapping is what an anonymous client sees under `AnonymousPolicy.IntrospectionOnly`.
  // The HTTP tests above cover the query type. These tests cover the mutation and the subscription
  // root types directly, because the mapping lists the root types one by one.
  private lazy val introspectionOnly: GraphQLService[IO] =
    GraphQLService.unvalidated[IO](IntrospectionMapping[IO](TestMapping.schema))

  // Compiles the document with the introspection mapping and asserts the message of the refusal.
  private def assertRejects(query: String, message: String): Unit =
    introspectionOnly.parse(RequestContext.empty, query, None, None) match
      case Result.Failure(ps) => assertEquals(ps.head.message, message)
      case other              => fail(s"Expected a failure, got $other")

  test("The introspection mapping rejects a mutation field."):
    assertRejects("mutation { slowUpdate }", "Field 'slowUpdate' requires authentication.")

  test("The introspection mapping rejects a subscription field."):
    assertRejects("subscription { echo(s: \"hi\") }", "Field 'echo' requires authentication.")
