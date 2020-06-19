package io.janstenpickle.controller.coordinator

import java.nio.file.{Path, Paths}

import cats.effect.{ExitCode, IO, IOApp}
import extruder.core.Parser
import extruder.typesafe._
import extruder.refined._
import fs2.Stream
import io.janstenpickle.controller.coordinator.config.Configuration
import io.janstenpickle.controller.server.Server
import io.janstenpickle.controller.coordinator.environment.Module

import scala.util.Try

object Main extends IOApp {

  implicit val pathParser: Parser[Path] = Parser.fromTry(path => Try(Paths.get(path)))

  override def run(args: List[String]): IO[ExitCode] =
    Server[IO, Configuration.Config](
      args.headOption,
      (config, _) => Stream.resource(Module.components[IO](config)).map { case (routes, reg) => (routes, reg, None) }
    ).compile.toList
      .map(_.head)
}
