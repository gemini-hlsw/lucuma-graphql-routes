// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.service

import edu.gemini.odb.api.schema.ProgramSchema
import edu.gemini.odb.api.repo.OdbRepo
import cats.implicits._
import cats.effect.{Async, Effect}
import io.circe._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, InvalidMessageBodyFailure, ParseFailure, QueryParamDecoder, Response}
import sangria.execution._
import sangria.marshalling.circe._
import sangria.ast.Document
import sangria.parser.{QueryParser, SyntaxError}
import sangria.validation.AstNodeViolation

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}


trait OdbService[F[_]]{

  def runQuery(
    query: String,
    op:    Option[String],
    vars:  Option[Json]
  ): F[Either[Json, Json]]

}


object OdbService {
  def routes[F[_]: Async](service: OdbService[F]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F]{}
    import dsl._

    implicit val jsonQPDecoder: QueryParamDecoder[Json] = QueryParamDecoder[String].emap { s =>
      parser.parse(s).leftMap { case ParsingFailure(msg, _) => ParseFailure("Invalid variables", msg) }
    }

    object QueryMatcher extends QueryParamDecoderMatcher[String]("query")
    object OperationNameMatcher extends OptionalQueryParamDecoderMatcher[String]("operationName")
    object VariablesMatcher extends OptionalValidatingQueryParamDecoderMatcher[Json]("variables")

    def toResponse(result: Either[Json, Json]): F[Response[F]] =
      result match {
        case Left(err)   => BadRequest(err)
        case Right(json) => Ok(json)
      }

    HttpRoutes.of[F] {

      // GraphQL query is embedded in the URI query string when queried via GET
      case GET -> Root / "odb" :?  QueryMatcher(query) +& OperationNameMatcher(op) +& VariablesMatcher(vars0) =>
        vars0.sequence.fold(
          errors => BadRequest(errors.map(_.sanitized).mkString_("", ",", "")),
          vars   =>
            for {
              result <- service.runQuery(query, op, vars)
              resp   <- toResponse(result)
            } yield resp
          )

      // GraphQL query is embedded in a Json request body when queried via POST
      case req @ POST -> Root / "odb" =>
        for {
          body   <- req.as[Json]
          obj    <- body.asObject.liftTo[F](InvalidMessageBodyFailure("Invalid GraphQL query"))
          query  <- obj("query").flatMap(_.asString).liftTo[F](InvalidMessageBodyFailure("Missing query field"))
          op     =  obj("operationName").flatMap(_.asString)
          vars   =  obj("variables")
          result <- service.runQuery(query, op, vars)
          resp   <- toResponse(result)
        } yield resp
    }
  }

  private object format {

    // Format a SyntaxError as a GraphQL `errors`
    def syntaxError(e: SyntaxError): Json =
      Json.obj(
        "errors" -> Json.arr(
          Json.obj(
            "message"   -> Json.fromString(e.getMessage),
            "locations" -> Json.arr(
              Json.obj(
                "line"   -> Json.fromInt(e.originalError.position.line),
                "column" -> Json.fromInt(e.originalError.position.column)
              )
            )
          )
        )
      )

    // Format a WithViolations as a GraphQL `errors`
    def withViolations(e: WithViolations): Json =
      Json.obj(
        "errors" -> Json.fromValues(e.violations.map {
          case v: AstNodeViolation =>
            Json.obj(
              "message"   -> Json.fromString(v.errorMessage),
              "locations" -> Json.fromValues(v.locations.map(loc =>
                Json.obj(
                  "line" -> Json.fromInt(loc.line),
                  "column" -> Json.fromInt(loc.column)
                )
              ))
            )
        case v                    =>
          Json.obj(
            "message" -> Json.fromString(v.errorMessage)
          )
      }))

    // Format a String as a GraphQL `errors`
    //      private def formatString(s: String): Json = Json.obj(
    //        "errors" -> Json.arr(Json.obj(
    //          "message" -> Json.fromString(s))))

    // Format a Throwable as a GraphQL `errors`
    def throwable(e: Throwable): Json =
      Json.obj(
        "errors" -> Json.arr(
          Json.obj(
            "class"   -> Json.fromString(e.getClass.getName),
            "message" -> Json.fromString(e.getMessage)
          )
        )
      )
  }


  def service[F[_]](odb: OdbRepo[F])(implicit F: Effect[F]): OdbService[F] =
    new OdbService[F] {

      // Lift a `Json` into the error side of our effect.
      def fail(j: Json): F[Either[Json, Json]] =
        F.pure(j.asLeft)

      override def runQuery(
        query: String,
        op:    Option[String],
        vars:  Option[Json]
      ): F[Either[Json, Json]] =

        QueryParser.parse(query) match {
          case Success(ast)                      => exec(ast, op, vars)
          case Failure(e @ SyntaxError(_, _, _)) => fail(format.syntaxError(e))
          case Failure(e)                        => fail(format.throwable(e))
        }

      private def exec(
        d: Document,
        o: Option[String],
        v: Option[Json]
      ): F[Either[Json, Json]] =
        F.async { (cb: Either[Throwable, Json] => Unit) =>
          Executor.execute(
            schema           = ProgramSchema[F],
            queryAst         = d,
            userContext      = odb,
            operationName    = o,
            variables        = v.getOrElse(Json.fromJsonObject(JsonObject())),
            exceptionHandler = ProgramSchema.exceptionHandler
          ).onComplete {
            case Success(value) => cb(Right(value))
            case Failure(error) => cb(Left(error))
          }
      }.attempt.flatMap {
          case Right(json)               => F.pure(json.asRight[Json])
          case Left(err: WithViolations) => fail(format.withViolations(err))
          case Left(err)                 => fail(format.throwable(err))
      }
    }
}