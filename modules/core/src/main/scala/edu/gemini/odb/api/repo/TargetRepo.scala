// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.repo

import edu.gemini.odb.api.model.{InputError, Program, Target, ValidatedInput}
import cats.{Applicative, MonadError}
import cats.implicits._
import cats.effect.concurrent.Ref
import edu.gemini.odb.api.model.InputError.MissingReference

sealed trait TargetRepo[F[_]] {

  def nextId: F[Target.Id]

  def selectAll: F[List[Target]]

  def select(tid: Target.Id): F[Option[Target]]

  def selectAllForProgram(pid: Program.Id): F[List[Target]]

  def insertSidereal(input: Target.CreateSidereal): F[Target]

  def delete(tid: Target.Id): F[Option[Target]]

}

object TargetRepo {

  def create[F[_]: Applicative](r: Ref[F, Tables])(implicit M: MonadError[F, Throwable]): TargetRepo[F] =

    new TargetRepo[F] {

      private def nextTargetId(t: Tables): (Tables, Target.Id) = {
        val tʹ = Tables.lastTargetId.modify(_.next)(t)
        (tʹ, tʹ.lastTargetId)
      }

      override def nextId: F[Target.Id] =
        r.modify(nextTargetId)

      override def selectAll: F[List[Target]] =
        r.get.map(_.targets.values.toList)

      override def select(id: Target.Id): F[Option[Target]] =
        r.get.map(Tables.target(id).get)

      override def selectAllForProgram(pid: Program.Id): F[List[Target]] =
        r.get.map(_.targets.toList.collect {
          case (_, t) if t.pid === pid => t
        })

      private def lookupProgram(pid: Program.Id, t: Tables): ValidatedInput[Program] =
        t.programs.get(pid).toValidNec(MissingReference("pid", pid.stringValue))

      override def insertSidereal(input: Target.CreateSidereal): F[Target] =
        r.modify { t =>
          val (tʹ, tid) = nextTargetId(t)
          (input.toGemTarget, lookupProgram(input.pid, tʹ)).mapN(
            (g, _) => Target(tid, input.pid, g)
          ).fold(
            err    => (t, err.asLeft[Target]),
            target => (Tables.targets.modify(_ + (tid -> target))(tʹ), target.asRight)
          )
        }.flatMap {
          case Left(err)     => M.raiseError[Target](InputError.Exception(err))
          case Right(target) => M.pure(target)
        }

      override def delete(tid: Target.Id): F[Option[Target]] =
        r.getAndUpdate(Tables.targets.modify(_ - tid))
         .map(Tables.target(tid).get)

    }

}
