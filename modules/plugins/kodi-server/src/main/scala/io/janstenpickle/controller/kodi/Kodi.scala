package io.janstenpickle.controller.kodi

import java.net.InetAddress
import java.nio.file.{Path, Paths}

import cats.effect.{ExitCode, IO, IOApp}
import extruder.core.Parser
import extruder.typesafe._
import extruder.refined._
import io.janstenpickle.controller.server.Server
import fs2.Stream

import scala.util.Try

object Kodi extends IOApp {
  implicit val inetAddressParser: Parser[InetAddress] = Parser.fromTry(addr => Try(InetAddress.getByName(addr)))
  implicit val pathParser: Parser[Path] = Parser.fromTry(path => Try(Paths.get(path)))

  override def run(args: List[String]): IO[ExitCode] =
    Server[IO, Configuration.Config](
      args.headOption,
      (config, _) => Stream.resource(Module.components[IO](config)).map { case (routes, reg) => (routes, reg, None) }
    ).compile.toList
      .map(_.head)
}
