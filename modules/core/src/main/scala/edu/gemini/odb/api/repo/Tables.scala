// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.repo

import edu.gemini.odb.api.model.{Program, Target}

import monocle.Lens
import monocle.function.At

import cats.implicits._
import scala.collection.immutable.{SortedMap, TreeMap}

/**
 *
 */
final case class Tables(
  lastProgramId: Program.Id,
  programs:      SortedMap[Program.Id, Program],
  lastTargetId:  Target.Id,
  targets:       SortedMap[Target.Id, Target]
)

object Tables {

  val empty: Tables =
    Tables(
      lastProgramId = Program.Id.zero,
      programs      = TreeMap.empty[Program.Id, Program],
      lastTargetId  = Target.Id.zero,
      targets       = TreeMap.empty[Target.Id, Target]
    )

  val lastProgramId: Lens[Tables, Program.Id] =
    Lens[Tables, Program.Id](_.lastProgramId)(b => a => a.copy(lastProgramId = b))

  val programs: Lens[Tables, SortedMap[Program.Id, Program]] =
    Lens[Tables, SortedMap[Program.Id, Program]](_.programs)(b => a => a.copy(programs = b))

  def program(pid: Program.Id): Lens[Tables, Option[Program]] =
    programs.composeLens(At.at(pid))

  val lastTargetId: Lens[Tables, Target.Id] =
    Lens[Tables, Target.Id](_.lastTargetId)(b => a => a.copy(lastTargetId = b))

  val targets: Lens[Tables, SortedMap[Target.Id, Target]] =
    Lens[Tables, SortedMap[Target.Id, Target]](_.targets)(b => a => a.copy(targets = b))

  def target(tid: Target.Id): Lens[Tables, Option[Target]] =
    targets.composeLens(At.at(tid))

}