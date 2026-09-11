// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import grackle.QueryCompiler.IntrospectionLevel
import grackle.Result
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json

import BaseSuite.ClientOption.*

class IntrospectionLevelSuite extends BaseSuite:

  val graphQLService: GraphQLService[IO] =
    GraphQLService.unvalidated(TestMapping)

  override def routesConfig: RoutesConfig =
    RoutesConfig(introspection = IntrospectionLevel.Disabled)

  test("[http] Disabled rejects __schema for an authenticated client."):
    interceptGraphQL("No field '__schema' for type Query")(query(None, "query { __schema { queryType { name } } }", None, Http))

  test("[http] Disabled still runs a data query."):
    query(None, "query { echo(s: \"hi\") }", None, Http).void

  test("[ws] Disabled rejects __schema for an authenticated client."):
    interceptGraphQL("No field '__schema' for type Query")(query(None, "query { __schema { queryType { name } } }", None, Ws))

object TypenameMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query { thing: Thing! }
    type Thing { name: String! }
  """
  val QueryType = schema.ref("Query")
  val ThingType = schema.ref("Thing")
  val typeMappings = TypeMappings.unchecked(
    ObjectMapping(QueryType, List(
      CursorFieldJson("thing", _ => Result(Json.obj()), Nil)
    )),
    ObjectMapping(ThingType, List(
      CursorFieldJson("name", _ => Result(Json.fromString("a thing")), Nil)
    ))
  )

class TypenameOnlySuite extends BaseSuite:

  val graphQLService: GraphQLService[IO] =
    GraphQLService.unvalidated(TypenameMapping)

  override def routesConfig: RoutesConfig =
    RoutesConfig(introspection = IntrospectionLevel.TypenameOnly)

  test("[http] TypenameOnly rejects __schema."):
    interceptGraphQL("Introspection is disabled")(query(None, "query { __schema { queryType { name } } }", None, Http))

  test("[http] TypenameOnly answers __typename."):
    query(None, "query { thing { __typename name } }", None, Http).void
