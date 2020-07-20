// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.odb.api.service

import java.util.concurrent._

import scala.concurrent.ExecutionContext.global
import cats.effect.{Blocker, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Timer}
import cats.implicits._
import edu.gemini.odb.api.repo.OdbRepo
import fs2.Stream
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.Logger
import org.http4s.server.staticcontent._

// #server
object Main extends IOApp {

  def stream[F[_]: ConcurrentEffect : ContextShift](odb: OdbRepo[F])(implicit T: Timer[F]): Stream[F, Nothing] = {
    val blockingPool = Executors.newFixedThreadPool(4)
    val blocker      = Blocker.liftExecutorService(blockingPool)
    val odbService   = OdbService.service[F](odb)

    val httpApp0 = (
      // Routes for static resources, ie. GraphQL Playground
      resourceService[F](ResourceService.Config("/assets", blocker)) <+>
      // Routes for the ODB GraphQL service
      OdbService.routes[F](odbService)
    ).orNotFound

    val httpApp = Logger.httpApp(logHeaders = true, logBody = false)(httpApp0)

    // Spin up the server ...
    for {
      exitCode <- BlazeServerBuilder[F](global)
        .bindHttp(8080, "0.0.0.0")
        .withHttpApp(httpApp)
        .serve
    } yield exitCode
  }.drain

  def run(args: List[String]): IO[ExitCode] =
    for {
      odb <- OdbRepo.create[IO]
      _   <- Init.initialize[IO].apply(odb)
      _   <- stream[IO](odb).compile.drain
    } yield ExitCode.Success
}

