// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import fs2.Stream
import grackle.Result
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json
import io.circe.literal.*
import org.http4s.Method
import org.http4s.Request
import org.http4s.Status
import org.http4s.circe.*
import org.http4s.headers.Authorization
import org.http4s.jdkhttpclient.JdkHttpClient

import scala.concurrent.duration.*

import BaseSuite.ClientOption.*

// Mapping used by SubscriptionOverHttpSuite. Provides a query, a mutation, and a subscription
// with both a finite (3-element) stream and an infinite stream.
object SubscriptionHttpMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query {
      ping: String!
    }
    type Mutation {
      noop: Boolean!
    }
    type Subscription {
      finite: Int!
      infinite: Int!
    }
  """

  val QueryType        = schema.ref("Query")
  val MutationType     = schema.ref("Mutation")
  val SubscriptionType = schema.ref("Subscription")

  val typeMappings = List(
    ObjectMapping(QueryType, List(
      CursorFieldJson("ping", _ => Result.success(Json.fromString("pong")), Nil)
    )),
    ObjectMapping(MutationType, List(
      CursorFieldJson("noop", _ => Result.success(Json.fromBoolean(true)), Nil)
    )),
    ObjectMapping(SubscriptionType, List(
      RootStream.computeJson("finite"): (_, _) =>
        Stream.emits(List(1, 2, 3)).covary[IO].map(n => Result.success(Json.fromInt(n))),
      RootStream.computeJson("infinite"): (_, _) =>
        Stream.constant(Result.success(Json.fromInt(0))).covary[IO]
    ))
  )

class SubscriptionOverHttpSuite extends BaseSuite:

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(SubscriptionHttpMapping).some.pure[IO]

  // Send a raw HTTP request to /graphql and return (status-code, parsed JSON body).
  private def rawRequest(query: String, method: Method): IO[(Status, Json)] =
    JdkHttpClient.simple[IO].use { client =>
      val svr     = serverFixture()
      val baseUri = svr.baseUri / "graphql"
      val req = method match {
        case Method.POST =>
          Request[IO](Method.POST, baseUri)
            .withEntity(Json.obj("query" -> Json.fromString(query)))
        case _ =>
          Request[IO](method, baseUri.withQueryParam("query", query))
      }
      client.run(req).use(resp => resp.as[Json].map(body => (resp.status, body)))
    }

  // Assert that the response is 422 Unprocessable Content with a well-formed GraphQL JSON body
  // whose first error message names subscriptions as the problem.
  private def assert422SubscriptionError(status: Status, body: Json): Unit =
    assertEquals(status.code, 422)
    val errors = body.hcursor.downField("errors").as[List[Json]].getOrElse(Nil)
    assert(errors.nonEmpty, s"Expected 'errors' in body, but got: ${body.spaces2}")
    val msg = errors.head.hcursor.downField("message").as[String].getOrElse("")
    assert(msg.toLowerCase.contains("subscription"),
      s"Expected message to mention 'subscription', got: $msg")

  test("[http, POST] subscription returns 422 with JSON errors body"):
    rawRequest("subscription { finite }", Method.POST).map { (status, body) =>
      assert422SubscriptionError(status, body)
    }

  test("[http, GET] subscription returns 422 with JSON errors body"):
    rawRequest("subscription { finite }", Method.GET).map { (status, body) =>
      assert422SubscriptionError(status, body)
    }

  // A subscription backed by an infinite stream must not hang. The 5-second timeout turns a
  // regression (server blocks forever collecting the stream) into a fast failure.
  test("[http, POST] subscription over infinite stream returns 422 promptly"):
    rawRequest("subscription { infinite }", Method.POST)
      .timeout(5.seconds)
      .map { (status, body) =>
        assert422SubscriptionError(status, body)
      }

  test("[http, POST] subscription over multi-element stream returns 422, not 'Expected exactly one result'"):
    rawRequest("subscription { finite }", Method.POST).map { (status, body) =>
      assert422SubscriptionError(status, body)
      val msg = body.hcursor.downField("errors").downArray.downField("message").as[String].getOrElse("")
      assert(!msg.contains("Expected exactly one result"),
        s"Got old 'Expected exactly one result' error: $msg")
    }

  test("[http, POST] query still returns 200"):
    expect(
      bearerToken = none,
      query       = "query { ping }",
      expected    = Right(json"""{ "ping": "pong" }"""),
      variables   = none,
      client      = Http
    )

  test("[http, POST] mutation still returns 200"):
    expect(
      bearerToken = none,
      query       = "mutation { noop }",
      expected    = Right(json"""{ "noop": true }"""),
      variables   = none,
      client      = Http
    )

  test("[ws, subscription] subscription over WebSocket still works"):
    subscriptionExpect(
      bearerToken = none,
      query       = "subscription { finite }",
      mutations   = Right(IO.unit),
      expected    = List(
        json"""{ "finite": 1 }""",
        json"""{ "finite": 2 }""",
        json"""{ "finite": 3 }"""
      ),
      variables   = none
    )
