// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import fs2.Stream
import grackle.Query.Binding
import grackle.QueryCompiler.Elab
import grackle.QueryCompiler.SelectElaborator
import grackle.Result
import grackle.Value.StringValue
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json

import scala.concurrent.duration.*

// The GraphQL schema and mapping that the test suites share.

object TestMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query {
      echo(s: String): String!
      slow: String!
    }
    type Mutation {
      slowUpdate: String!
    }
    type Subscription {
      echo(s: String): String!
      empty: String!
      ticks: String!
    }
  """
  val QueryType        = schema.ref("Query")
  val MutationType     = schema.ref("Mutation")
  val SubscriptionType = schema.ref("Subscription")
  // A single result that arrives after 10 seconds, so a test can act while it runs.
  private val slowResult: IO[Result[Json]] =
    IO.sleep(10.seconds).as(Result(Json.fromString("done")))
  val typeMappings     = TypeMappings.unchecked(
    ObjectMapping(QueryType, List(
      CursorFieldJson("echo", c => c.envR[String]("s").map(Json.fromString), Nil),
      RootEffect.computeJson("slow")((_, _) => slowResult)
    )),
    ObjectMapping(MutationType, List(
      RootEffect.computeJson("slowUpdate")((_, _) => slowResult)
    )),
    ObjectMapping(SubscriptionType, List(
      RootStream.computeJson("echo"): (_, e) =>
        val r = e.getR[String]("s").map(Json.fromString)
        Stream(r, r, r).covary[IO],
      RootStream.computeJson("empty"): (_, _) =>
        Stream.empty.covary[IO],
      // A source stream that never ends, so a test can close the subscription while it runs.
      RootStream.computeJson("ticks"): (_, _) =>
        Stream.awakeEvery[IO](25.milliseconds).as(Result(Json.fromString("tick")))
    ))
  )
  override val selectElaborator = SelectElaborator:
    case (_, "echo", List(Binding("s", StringValue(s)))) => Elab.env("s" -> s)
