// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.model

import gem.`enum`.EphemerisKeyType
import gsp.math
import cats.implicits._
import io.circe.Decoder

final case class Target(
  id:       Target.Id,
  pid:      Program.Id,
  target:   gem.Target
)

object Target {
  abstract case class Id private (index: Int) extends ObjectId {
    assert(index >= 0, s"ids must have positive indices, not: $index")

    def prefix: String =
      Id.prefix

    def next: Id =
      Id.unsafeFromInt(index + 1)
  }

  object Id extends ObjectIdType[Id] {
    override def prefix: String =
      "target"

    override def fromInt(i: Int): Option[Id] =
      if (i >= 0) Some(new Id(i) {}) else None
  }

  object parse {

    def ephemerisKey(fieldName: String, key: EphemerisKeyType, input: String): ValidatedInput[gem.EphemerisKey] =
      gem.EphemerisKey
        .fromTypeAndDes
        .getOption((key, input))
        .toValidNec(
          InputError.InvalidField(fieldName, input, s"Invalid description for ephemeris key type `${key.shortName}`")
        )

    def ra(fieldName: String, input: String): ValidatedInput[math.RightAscension] =
      math.RightAscension
        .fromStringHMS
        .getOption(input)
        .toValidNec(
          InputError.InvalidField(fieldName, input, "Expected right ascension in format HH:MM:SS.SSS")
        )

    def dec(fieldName: String, input: String): ValidatedInput[math.Declination] =
      math.Declination
        .fromStringSignedDMS
        .getOption(input)
        .toValidNec(
          InputError.InvalidField(fieldName, input, "Expected declination in format [+/-]DD:MM:SS:SS.SSS")
        )

  }

  /*
  sealed trait Tracking

  final case class NonSiderealTracking(
    key:   EphemerisKeyType,
    des:   String,
  ) extends Tracking

  object NonSiderealTracking {
    def fromEphemerisKey(k: gem.EphemerisKey): NonSiderealTracking =
      NonSiderealTracking(
        k.keyType,
        k.des
      )
  }
   */

  /*
  final case class CreateNonSiderealTarget(
    pid:  Program.Id,
    name: String,
    key:  EphemerisKeyType,
    des:  String
  ) {

    val toEphemerisKey: Result[gem.EphemerisKey] =
      parse.ephemerisKey("des", key, des)

    val toTarget: Result[Target] =
      toEphemerisKey.map { k =>
        Target(
          pid,
          name,
          NonSidereal.fromEphemerisKey(k)
        )
      }
  }
   */

  /*
  final case class SiderealTracking(
    ra:     String,
    dec:    String
  ) extends Tracking

  object SiderealTracking {
    def fromProperMotion(p: math.ProperMotion): SiderealTracking =
      SiderealTracking(
        math.RightAscension.fromStringHMS.reverseGet(p.baseCoordinates.ra),
        math.Declination.fromStringSignedDMS.reverseGet(p.baseCoordinates.dec)
      )
  }
   */

  final case class CreateSidereal(
    pid:  Program.Id,
    name: String,
    ra:   String,
    dec:  String
  ) {

    val toProperMotion: ValidatedInput[math.ProperMotion] =
      (parse.ra("ra", ra),
       parse.dec("dec", dec)
      ).mapN(
        (ra, dec) =>
          math.ProperMotion(
            math.Coordinates(ra, dec), math.Epoch.J2000, None, None, None
          )
      )

    val toGemTarget: ValidatedInput[gem.Target] =
      toProperMotion.map { pm => gem.Target(name, Right(pm)) }

  }

  object CreateSidereal {
    implicit val DecoderCreateSidereal: Decoder[CreateSidereal] =
      Decoder.forProduct4("pid", "name", "ra", "dec")(CreateSidereal.apply)
  }

}
