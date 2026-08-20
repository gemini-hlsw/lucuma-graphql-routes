// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.data.NonEmptyList
import cats.syntax.all.*
import grackle.Problem
import io.circe.Json
import io.circe.syntax.*
import org.http4s.Charset
import org.http4s.Headers
import org.http4s.MediaType
import org.http4s.QValue
import org.http4s.Response
import org.http4s.Status
import org.http4s.circe.*
import org.http4s.headers.Accept
import org.http4s.headers.`Content-Type`

/**
 * The media type that the server selected for a response, as described by the GraphQL over HTTP
 * specification.
 *
 * See https://github.com/graphql/graphql-over-http/blob/main/spec/GraphQLOverHTTP.md
 */
enum ResponseMediaType:

  /** The client accepts `application/graphql-response+json`. */
  case GraphQL

  /**
   * The client accepts `application/json` but not `application/graphql-response+json`. The
   * specification calls such a client a legacy client.
   */
  case LegacyJson

  /**
   * The `Content-Type` header of a response with the given status.
   *
   * The specification asks for the GraphQL media type, except that a 2xx response to a legacy
   * client carries `application/json`.
   */
  def contentType(status: Status): `Content-Type` =
    val mediaType =
      if this == ResponseMediaType.LegacyJson && status.isSuccess then ResponseMediaType.Json
      else ResponseMediaType.GraphQLResponseJson
    `Content-Type`(mediaType, Charset.`UTF-8`)

  /**
   * The status of a response that has both a `data` entry and an `errors` entry.
   *
   * The specification recommends status 294 together with the GraphQL media type, which gives the
   * status its meaning. A legacy client does not get that media type on a 2xx response, so it gets
   * status 200 instead.
   */
  def partialSuccessStatus: Status = this match
    case ResponseMediaType.GraphQL    => ResponseMediaType.PartialSuccess
    case ResponseMediaType.LegacyJson => Status.Ok

  /** A response with the given status and the given GraphQL response body. */
  def response[F[_]](status: Status, body: Json): Response[F] =
    Response[F](status).withEntity(body).withContentType(contentType(status))

  /** A response that carries the given error messages and no data. */
  def errorResponse[F[_]](status: Status, messages: NonEmptyList[String]): Response[F] =
    response[F](status, Json.obj("errors" -> messages.toList.map(m => Problem(m)).asJson))

  /** A response that carries one error message and no data. */
  def errorResponse[F[_]](status: Status, message: String): Response[F] =
    errorResponse[F](status, NonEmptyList.one(message))

object ResponseMediaType:

  /** The media type for a GraphQL response. */
  val GraphQLResponseJson: MediaType =
    new MediaType("application", "graphql-response+json", compressible = true, binary = false)

  /** The media type for a GraphQL request body, and for a response to a legacy client. */
  val Json: MediaType =
    MediaType.application.json

  /**
   * Status code 294, when the response has both a `data` entry and an `errors` entry.
   */
  val PartialSuccess: Status =
    Status
      .fromInt(294)
      .getOrElse(throw new AssertionError("294 Partial Success is a valid status code"))

  /**
   * Select the media type for a response to a request with the given headers.
   *
   * The specification leaves the choice to the server when the request has no `Accept` header.
   * This server then uses the GraphQL media type.
   *
   * A result of `None` means that the server supports no media type that the client accepts. The
   * caller must then answer with status 406.
   */
  def negotiate(headers: Headers): Option[ResponseMediaType] =
    headers.get[Accept] match
      case None         => GraphQL.some
      case Some(accept) =>
        // Extract the highest q value of that media type
        def priority(mediaType: MediaType): Option[QValue] =
          accept.values.toList
            .filter(entry => entry.mediaRange.satisfiedBy(mediaType) && entry.qValue > QValue.Zero)
            .map(_.qValue)
            .maximumOption

        (priority(GraphQLResponseJson), priority(Json)) match
          case (Some(graphQL), Some(json)) => (if json > graphQL then LegacyJson else GraphQL).some
          case (Some(_), None)             => GraphQL.some
          case (None, Some(_))             => LegacyJson.some
          case (None, None)                => none
