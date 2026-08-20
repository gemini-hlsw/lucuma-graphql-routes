// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import grackle.Context
import grackle.Result
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Encoder
import io.circe.Json
import org.http4s.*
import org.http4s.headers.Allow
import org.http4s.headers.Authorization
import org.http4s.jdkhttpclient.JdkHttpClient

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

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(GetMutationMapping).some.pure[IO]

  // Issue a real HTTP GET to the /graphql endpoint, bypassing the FetchClient
  // (which always POSTs). Returns the response status and the Allow header, if
  // present. Both are extracted inside `use` so the response body is never
  // needed after the resource is released.
  private def rawGet(
    query:         String,
    operationName: Option[String] = None
  ): IO[(Status, Option[Allow])] =
    Resource.eval(IO(serverFixture())).flatMap { svr =>
      JdkHttpClient.simple[IO].flatMap { client =>
        val uri0 = (svr.baseUri / "graphql").withQueryParam("query", query)
        val uri1 = operationName.fold(uri0)(n => uri0.withQueryParam("operationName", n))
        client.run(Request[IO](Method.GET, uri1))
      }
    }.use(resp => IO.pure((resp.status, resp.headers.get[Allow])))

  private val mutationDoc = "mutation { increment }"
  private val queryDoc    = "query { ping }"
  // A document that contains both a query and a mutation operation, so we can
  // verify that the operation-name selector is what matters, not the document.
  private val mixedDoc    = "query Ping { ping } mutation Inc { increment }"

  // --- core correctness: mutation on GET must be rejected -----

  test("GET a mutation returns 405 Method Not Allowed"):
    rawGet(mutationDoc).map { (status, _) =>
      assertEquals(status, Status.MethodNotAllowed)
    }

  test("GET a mutation response includes Allow: POST"):
    rawGet(mutationDoc).map { (_, allow) =>
      assertEquals(allow, Some(Allow(Method.POST)))
    }

  // This is the most important assertion: the mutation side-effect must NOT
  // happen when the request is rejected at the HTTP layer.
  test("GET a mutation does not execute the mutation side effect"):
    val before = GetMutationMapping.counter.get()
    rawGet(mutationDoc).map { _ =>
      assertEquals(GetMutationMapping.counter.get(), before,
        "Counter must not change when a mutation is rejected via GET")
    }

  // --- regression: query over GET must still work ----------------

  test("GET a query still returns 200 OK"):
    rawGet(queryDoc).map { (status, _) =>
      assertEquals(status, Status.Ok)
    }

  // --- regression: mutation over POST must still execute ---------

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
    rawGet(mixedDoc, Some("Ping")).map { (status, _) =>
      assertEquals(status, Status.Ok)
    }

  test("GET a mutation operation from a mixed document returns 405"):
    rawGet(mixedDoc, Some("Inc")).map { (status, allow) =>
      assertEquals(status, Status.MethodNotAllowed)
      assertEquals(allow, Some(Allow(Method.POST)))
    }
