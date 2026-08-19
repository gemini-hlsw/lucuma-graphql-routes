// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.*
import cats.implicits.*
import clue.ResponseException
import fs2.Stream
import grackle.Result
import grackle.circe.CirceMapping
import grackle.syntax.*
import io.circe.Json
import io.circe.literal.*
import org.http4s.headers.Authorization

object StreamErrorMapping extends CirceMapping[IO]:
  val schema = schema"""
    type Query { dummy: Boolean }
    type Subscription { failing: String! }
  """
  val QueryType        = schema.ref("Query")
  val SubscriptionType = schema.ref("Subscription")
  val typeMappings = TypeMappings.unchecked(
    ObjectMapping(SubscriptionType, List(
      RootStream.computeJson("failing"): (_, _) =>
        // Emits two results, then fails.
        Stream(
          Result.success(Json.fromString("first")),
          Result.success(Json.fromString("second"))
        ).covary[IO] ++
          Stream.raiseError[IO](new RuntimeException("boom"))
    ))
  )

class StreamErrorSuite extends BaseSuite:

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(StreamErrorMapping).some.pure[IO]

  test("stream error: client receives partial results then an error message"):
    for
      errorRef <- IO.ref(Option.empty[ResponseException[Json]])
      results  <- subscription(
        bearerToken = none,
        query       = "subscription { failing }",
        mutations   = Right(IO.unit),
        variables   = none,
        onError     = e => errorRef.set(Some(e))
      )
      err      <- errorRef.get
      _        = assertEquals(
                   results,
                   List(json"""{"failing":"first"}""", json"""{"failing":"second"}"""),
                   "expected the two emitted results before the failure"
                 )
      _        = assert(err.isDefined, "expected a ResponseException to be delivered via onError")
      _        = assert(
                   err.exists(_.errors.head.message.contains("Internal Error")),
                   s"unexpected error content: $err"
                 )
    yield ()
