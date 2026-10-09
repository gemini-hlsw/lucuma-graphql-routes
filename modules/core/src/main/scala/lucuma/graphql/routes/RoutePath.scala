// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import org.http4s.Uri.Path

/**
 * The path of an endpoint, as set in `RoutesConfig`. The path can have more than one segment, for
 * example `api/graphql`. The `Root / "..."` extractor of the DSL matches one segment only.
 */
private[routes] final case class RoutePath(segments: Vector[String], endsWithSlash: Boolean):

  /** The path relative to the root, with each segment encoded. */
  val path: Path =
    Path(segments.map(Path.Segment(_)), absolute = false, endsWithSlash = endsWithSlash)

  /**
   * Matches the path of a request. The match compares decoded segments, so an encoded `/` in the
   * request (`%2F`) does not split a segment.
   */
  def unapply(requestPath: Path): Boolean =
    requestPath.endsWithSlash == endsWithSlash && requestPath.segments.map(_.decoded()) == segments

  /** The absolute path, for a span name. For example `/api/graphql`. */
  override def toString: String =
    path.toAbsolute.renderString

private[routes] object RoutePath:

  def apply(config: String): RoutePath =
    val path = Path.unsafeFromString(config)
    RoutePath(path.segments.map(_.decoded()), path.endsWithSlash)
