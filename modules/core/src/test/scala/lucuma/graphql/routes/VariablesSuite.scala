// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import fs2.Stream
import grackle.Query.Binding
import grackle.QueryCompiler.Elab
import grackle.QueryCompiler.SelectElaborator
import grackle.Schema
import grackle.Value.StringValue
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json
import io.circe.literal.*
import natchez.Trace.Implicits.noop
import org.http4s.headers.Authorization

import BaseSuite.ClientOption
import BaseSuite.ClientOption.*

// This suite tests that variables make it through.

object VariablesMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query {
      echo(s: String): String!
    }
    type Subscription {
      echo(s: String): String!
    }
  """
  val QueryType        = schema.ref("Query")
  val SubscriptionType = schema.ref("Subscription")
  val typeMappings     = TypeMappings.unchecked(
    ObjectMapping(QueryType, List(
      CursorFieldJson("echo", c => c.envR[String]("s").map(Json.fromString), Nil)
    )),
    ObjectMapping(SubscriptionType, List(
      RootStream.computeJson("echo"): (_, e) =>
        val r = e.getR[String]("s").map(Json.fromString)
        Stream(r, r, r).covary[IO]
    ))
  )
  override val selectElaborator = SelectElaborator:
    case (_, "echo", List(Binding("s", StringValue(s)))) => Elab.env("s" -> s)

class VariablesSuite extends BaseSuite:
  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(VariablesMapping).some.pure[IO]

  def testQuery(option: ClientOption): IO[Unit] =
    expect(
      bearerToken = none,
      query       = """query($abc: String) { echo(s: $abc) }""",
      expected    = Right(json"""{ "echo": "foo" }"""), 
      variables   = Json.obj("abc" -> Json.fromString("foo")).asObject,
      client      = option
    )

  def testSubscription: IO[Unit] =
    subscriptionExpect(
      bearerToken = none,
      query       = """subscription($abc: String) { echo(s: $abc) }""",
      mutations   = Right(IO.unit),
      expected    = List.fill(3)(json"""{ "echo": "foo" }"""),
      variables   = Json.obj("abc" -> Json.fromString("foo")).asObject,
    )

  test("[http] Variables should be passed."):
    testQuery(Http)

  test("[ws, one-off] Variables should be passed."):
    testQuery(Ws)

  test("[ws, subscription] Variables should be passed."):
    testSubscription