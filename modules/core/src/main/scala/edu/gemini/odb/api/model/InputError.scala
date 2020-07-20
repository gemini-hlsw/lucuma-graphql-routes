// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.model

import cats.data.NonEmptyChain
import cats.implicits._

/**
 * Possible errors that are found in input types (arguments to mutations). These
 * are client-fixable issues, not execution errors.
 */
sealed trait InputError {
  def message: String

  def toException: InputError.Exception =
    InputError.Exception(NonEmptyChain(this))
}

object InputError {

  final case class Exception(nec: NonEmptyChain[InputError]) extends java.lang.Exception {
    override def getMessage: String =
      nec.map(_.message).intercalate("\n")
  }

  /**
   * Indicates that an input value does not conform to expectations.  For
   * example, an RA field that cannot be parsed.
   */
  final case class InvalidField(
    name:    String,
    input:   String,
    failure: String
  ) extends InputError {

    override def message: String =
      s"Could not validate $name field value `$input`: $failure"

  }


  /**
   * Indicates that an input contains an id whose referent could not be found.
   */
  final case class MissingReference(
    name:  String,
    value: String
  ) extends InputError {

    override def message: String =
      s"Could not find $name '$value''"

  }

}