// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.schema

import edu.gemini.odb.api.repo.OdbRepo
import cats.effect.Effect
import sangria.schema._

object NonsiderealType {

  val EphemerisKeyType: EnumType[gem.`enum`.EphemerisKeyType] =
    EnumType(
      "EphemerisKeyType",
      Some("Ephemeris key type options"),
      List(

        EnumValue(
          name = "ASTEROID_NEW",
          description = Some("Horizons Asteroid (New Format)"),
          value       = gem.`enum`.EphemerisKeyType.AsteroidNew
        ),

        EnumValue(
          name = "ASTEROID_OLD",
          description = Some("Horizons Asteroid (Old Format)"),
          value       = gem.`enum`.EphemerisKeyType.AsteroidOld
        ),

        EnumValue(
          name = "COMET",
          description = Some("Horizons Comet"),
          value       = gem.`enum`.EphemerisKeyType.Comet
        ),

        EnumValue(
          name = "MAJOR_BODY",
          description = Some("Horizons Major Body"),
          value       = gem.`enum`.EphemerisKeyType.MajorBody
        ),

        EnumValue(
          name = "USER_SUPPLIED",
          description = Some("Horizons User Supplied"),
          value       = gem.`enum`.EphemerisKeyType.UserSupplied
        )

      )
    )

  def apply[F[_]: Effect]: ObjectType[OdbRepo[F], gem.EphemerisKey] =
    ObjectType(
      name     = "Nonsidereal",
      fieldsFn = () => fields(

        Field(
          name        = "des",
          fieldType   = StringType,
          description = Some("Human readable designation that discriminates among ephemeris keys of the same type."),
          resolve     = _.value.des
        ),

        Field(
          name        = "keyType",
          fieldType   = EphemerisKeyType,
          description = Some("Nonsidereal target lookup type."),
          resolve     = _.value.keyType
        )
      )
    )

}
