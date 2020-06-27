package io.janstenpickle.controller.broadlink

import java.net.InetAddress
import java.nio.file.{Path, Paths}

import cats.effect.{ExitCode, IO, IOApp}
import com.github.mob41.blapi.mac.Mac
import extruder.core.Parser
import extruder.typesafe._
import extruder.refined._
import fs2.Stream
import io.janstenpickle.controller.server.Server

import scala.util.Try

object Broadlink extends IOApp {
  implicit val macParser: Parser[Mac] = Parser.fromTry(mac => Try(new Mac(mac)))
  implicit val inetAddressParser: Parser[InetAddress] = Parser.fromTry(addr => Try(InetAddress.getByName(addr)))
  implicit val pathParser: Parser[Path] = Parser.fromTry(path => Try(Paths.get(path)))

  override def run(args: List[String]): IO[ExitCode] =
    Server[IO, Configuration.Config](
      args.headOption,
      (config, _) => Stream.resource(Module.components[IO](config)).map { case (routes, reg) => (routes, reg, None) }
    ).compile.toList
      .map(_.head)
}
