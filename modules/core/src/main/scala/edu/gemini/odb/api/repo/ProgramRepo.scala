// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.repo

import cats.implicits._
import cats.{Monad, MonadError}
import cats.effect.concurrent.Ref
import edu.gemini.odb.api.model.Program

trait ProgramRepo[F[_]] {

  def nextId: F[Program.Id]

  def selectAll: F[List[Program]]

  def select(pid: Program.Id): F[Option[Program]]

  def unsafeSelect(pid: Program.Id): F[Program]

  def insert(input: Program.Create): F[Program]

  def delete(pid: Program.Id): F[Option[Program]]

}

object ProgramRepo {

  def create[F[_]: Monad](r: Ref[F, Tables])(implicit M: MonadError[F, Throwable]): ProgramRepo[F] =

    new ProgramRepo[F] {

      private def nextProgramId(t: Tables): (Tables, Program.Id) = {
        val tʹ = Tables.lastProgramId.modify(_.next)(t)
        (tʹ, tʹ.lastProgramId)
      }

      override def nextId: F[Program.Id] =
        r.modify(nextProgramId)

      override def selectAll: F[List[Program]] =
        r.get.map(_.programs.values.toList)

      override def select(pid: Program.Id): F[Option[Program]] =
        r.get.map(Tables.program(pid).get)

      override def unsafeSelect(pid: Program.Id): F[Program] =
        select(pid).flatMap {
          case None    => M.raiseError[Program](new RuntimeException(""))
          case Some(p) => p.pure[F]
        }

      override def insert(input: Program.Create): F[Program] =
        r.modify { t =>
          val (tʹ, pid) = nextProgramId(t)
          val program = Program(pid, input.name)
          (Tables.programs.modify(_ + (pid -> program))(tʹ), program)
        }

      override def delete(pid: Program.Id): F[Option[Program]] =
        r.getAndUpdate(Tables.programs.modify(_ - pid))
         .map(Tables.program(pid).get)

    }

}