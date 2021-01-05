package io.janstenpickle.controller.deconz

import java.net.InetAddress
import java.nio.file.{Path, Paths}

import cats.effect.{ExitCode, IO, IOApp}
import extruder.core.Parser
import extruder.refined._
import extruder.typesafe._
import fs2.Stream
import io.janstenpickle.controller.server.Server
import org.http4s.HttpRoutes

import scala.util.Try

object Deconz extends IOApp {

  implicit val inetAddressParser: Parser[InetAddress] = Parser.fromTry(addr => Try(InetAddress.getByName(addr)))
  implicit val pathParser: Parser[Path] = Parser.fromTry(path => Try(Paths.get(path)))

  override def run(args: List[String]): IO[ExitCode] =
    Server[IO, Configuration.Config](
      args.headOption,
      (config, _) =>
        Stream.resource(Module.components[IO](config)).map { reg =>
          (HttpRoutes.empty[IO], reg, None)
      }
    ).compile.toList
      .map(_.head)
}
