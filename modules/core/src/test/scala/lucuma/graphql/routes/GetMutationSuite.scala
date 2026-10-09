// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import grackle.Context
import grackle.Result
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json
import org.http4s.*
import org.http4s.headers.Allow

import java.util.concurrent.atomic.AtomicInteger

// Mapping used by GetMutationSuite. It exposes a trivial Query type and a
// Mutation type whose only field, `increment`, increments a shared counter and
// returns the new value. We use an AtomicInteger so it is accessible from both
// the server-side mapping and the test-side assertions without going through IO.
object GetMutationMapping extends CirceMapping[IO]:

  val counter: AtomicInteger = AtomicInteger(0)

  val schema = schema"""
    type Query {
      ping: String!
    }
    type Mutation {
      increment: Int!
    }
  """

  val QueryType    = schema.ref("Query")
  val MutationType = schema.ref("Mutation")

  val typeMappings = TypeMappings.unchecked(
    ObjectMapping(QueryType)(
      CursorField[String]("ping", _ => Result.success("pong"))
    ),
    ObjectMapping(MutationType)(
      // computeCursor: run the effect and return a CirceCursor whose focus is
      // a JSON object containing the mutation result. The interpreter then
      // calls cursor.field("increment") which looks up "increment" in the
      // JSON object and returns the Int leaf value.
      RootEffect.computeCursor("increment") { (_, env) =>
        IO(counter.incrementAndGet()).map { n =>
          val json = Json.obj("increment" -> Json.fromInt(n))
          CirceCursor(Context(MutationType), json, None, env).success
        }
      }
    )
  )

// Tests that GET requests correctly reject mutations and still allow queries.
class GetMutationSuite extends BaseSuite:

  val graphQLService: GraphQLService[IO] =
    GraphQLService.unvalidated(GetMutationMapping)

  private val mutationDoc = "mutation { increment }"
  // A document that contains both a query and a mutation operation, so we can
  // verify that the operation-name selector is what matters, not the document.
  private val mixedDoc    = "query Ping { ping } mutation Inc { increment }"

  // The specification requires status 405, the `Allow` header, and a well-formed GraphQL
  // response body with the GraphQL media type.
  test("GET a mutation returns 405 with Allow: POST and a GraphQL errors body"):
    jsonGet("query" -> mutationDoc).map: (status, headers, body) =>
      assertEquals(status, Status.MethodNotAllowed)
      assertEquals(headers.get[Allow], Some(Allow(Method.POST)))
      assertErrorBody(headers, body)

  // This is the most important assertion: the mutation side-effect must NOT
  // happen when the request is rejected at the HTTP layer.
  test("GET a mutation does not execute the mutation side effect"):
    val before = GetMutationMapping.counter.get()
    jsonGet("query" -> mutationDoc).map: _ =>
      assertEquals(GetMutationMapping.counter.get(), before,
        "Counter must not change when a mutation is rejected via GET")

  test("POST a mutation still executes and returns 200"):
    val before = GetMutationMapping.counter.get()
    this.query(
      bearerToken = None,
      query       = mutationDoc,
      variables   = None,
      client      = BaseSuite.ClientOption.Http
    ).map { json =>
      assertEquals(json, Json.obj("increment" -> Json.fromInt(before + 1)))
      assertEquals(GetMutationMapping.counter.get(), before + 1)
    }

  // --- mixed document: operation-name selection --------------------

  test("GET a query operation from a mixed document succeeds"):
    jsonGet("query" -> mixedDoc, "operationName" -> "Ping").map: (status, _, _) =>
      assertEquals(status, Status.Ok)

  test("GET a mutation operation from a mixed document returns 405"):
    jsonGet("query" -> mixedDoc, "operationName" -> "Inc").map: (status, headers, _) =>
      assertEquals(status, Status.MethodNotAllowed)
      assertEquals(headers.get[Allow], Some(Allow(Method.POST)))
