// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.model

import gsp.math.optics.Format
import atto.Atto.{char, int, letter, stringOf, _}
import atto._
import cats.implicits._
import cats.kernel.Order
import io.circe.Decoder

/**
 *
 */
trait ObjectId {
  def prefix: String
  def index:  Int

  def stringValue: String =
    s"$prefix${ObjectId.sep}$index"
}

object ObjectId {
  val sep: Char =
    '-'

  val parts: Parser[(String, Int)] =
    for {
      p <- stringOf(letter) <~ char(sep)
      i <- int.filter(n => n >= 0 && n <= Int.MaxValue).namedOpaque("uint")
    } yield (p, i)

  def parser[A](prefix: String)(f: Int => A): Parser[A] =
    parts.collect { case (p, i) if p === prefix => i }.map(f)

}

trait ObjectIdType[I <: ObjectId] {
  def prefix: String
  def fromInt(i: Int): Option[I]

  def unsafeFromInt(i: Int): I =
    fromInt(i).get

  lazy val zero: I =
    unsafeFromInt(0)

  lazy val parser: Parser[I] =
    ObjectId.parser(prefix)(unsafeFromInt)

  lazy val stringFormat: Format[String, I] =
    new Format[String, I](s => parser.parseOnly(s).option, _.stringValue)

  lazy val validFormat: String =
    s"$prefix${ObjectId.sep}#"

  implicit val OrderId: Order[I] =
    Order.by(_.index)

  implicit val DecoderId: Decoder[I] =
    Decoder.decodeString.emap { s =>
      stringFormat
        .getOption(s)
        .toRight(s"Invalid $prefix id '$s'.  Expected '$validFormat'")
    }

}

