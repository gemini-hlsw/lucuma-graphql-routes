// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import grackle.ValidationException
import grackle.circe.CirceMapping
import grackle.syntax.*
import munit.CatsEffectSuite
import org.typelevel.otel4s.trace.Tracer

// A mapping with a field that no ObjectMapping covers. Grackle reports it as a validation failure.
object BrokenMapping extends CirceMapping[IO]:
  val schema       = schema"""type Query { foo: Int, bar: Int }"""
  val QueryType    = schema.ref("Query")
  val typeMappings = TypeMappings.unchecked(ObjectMapping(QueryType, Nil))

class StartupValidationSuite extends CatsEffectSuite:

  given Tracer[IO] = Tracer.noop[IO]

  test("GraphQLService.apply raises for a mapping that does not validate."):
    GraphQLService[IO](BrokenMapping).intercept[ValidationException]

  test("GraphQLService.apply accepts a mapping that validates, and forces the compiler."):
    GraphQLService[IO](TestMapping).map(s => assertEquals(s.mapping, TestMapping))

  test("GraphQLService.unvalidated accepts a mapping that does not validate."):
    IO(GraphQLService.unvalidated[IO](BrokenMapping)).map(s =>
      assertEquals(s.mapping, BrokenMapping)
    )
