// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.schema

import cats.effect.Effect
import sangria.schema._

/**
 *
 */
object TrackingType {

  def apply[F[_]: Effect]: OutputType[Either[gem.EphemerisKey, gsp.math.ProperMotion]] = //UnionType[OdbRepo[F]] =
    UnionType(
      name        = "Tracking",
      description = Some("Either Nonsidereal ephemeris lookup key or Sidereal proper motion."),
      types       = List(NonsiderealType[F], SiderealType[F])
    ).mapValue[Either[gem.EphemerisKey, gsp.math.ProperMotion]](
      _.fold(
        key => key: Any,
        pm  => pm: Any
      )
    )

}
