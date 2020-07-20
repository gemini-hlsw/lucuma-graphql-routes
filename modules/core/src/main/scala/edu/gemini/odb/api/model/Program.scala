// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.model

final case class Program(
  id:   Program.Id,
  name: Option[String]
)

object Program {

  abstract case class Id private (index: Int) extends ObjectId {
    assert(index >= 0, s"ids must have positive indices, not: $index")

    def prefix: String =
      Id.prefix

    def next: Id =
      Id.unsafeFromInt(index + 1)
  }

  object Id extends ObjectIdType[Id] {
    override def prefix: String =
      "program"

    override def fromInt(i: Int): Option[Id] =
      if (i >= 0) Some(new Id(i) {}) else None
  }

  final case class Create(
    name: Option[String]
  )

}
