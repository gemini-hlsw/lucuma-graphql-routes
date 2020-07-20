// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.schema

import edu.gemini.odb.api.repo.OdbRepo
import cats.effect.Effect
import gsp.math
import sangria.schema._

object SiderealType {

  def apply[F[_]: Effect]: ObjectType[OdbRepo[F], math.ProperMotion] =
    ObjectType(
      name     = "Sidereal",
      fieldsFn = () => fields(

        Field(
          name        = "ra",
          fieldType   = StringType,
          description = Some("Right Ascension"),
          resolve     = v => math.RightAscension.fromStringHMS.reverseGet(v.value.baseCoordinates.ra)
        ),

        Field(
          name        = "dec",
          fieldType   = StringType,
          description = Some("Declination"),
          resolve     = v => math.Declination.fromStringSignedDMS.reverseGet(v.value.baseCoordinates.dec)
        )

      )
    )

}
