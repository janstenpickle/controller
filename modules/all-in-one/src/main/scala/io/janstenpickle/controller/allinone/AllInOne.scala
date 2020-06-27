package io.janstenpickle.controller.allinone

import java.net.InetAddress
import java.nio.file.{Path, Paths}

import cats.effect.{ExitCode, IO, IOApp}
import com.github.mob41.blapi.mac.Mac
import extruder.core.Parser
import io.janstenpickle.controller.allinone.config.Configuration.Config
import extruder.typesafe._
import extruder.refined._
import io.janstenpickle.controller.allinone.environment.Module
import fs2.Stream

import scala.util.Try

object AllInOne extends IOApp {
  implicit val pathParser: Parser[Path] = Parser.fromTry(path => Try(Paths.get(path)))
  implicit val macParser: Parser[Mac] = Parser.fromTry(mac => Try(new Mac(mac)))
  implicit val inetAddressParser: Parser[InetAddress] = Parser.fromTry(addr => Try(InetAddress.getByName(addr)))

  override def run(args: List[String]): IO[ExitCode] =
    io.janstenpickle.controller.server
      .Server[IO, Config](
        args.headOption,
        (config, _) =>
          Stream
            .resource(Module.components[IO](config))
            .map({
              case (routes, registry) => (routes, registry, None)
            })
      )
      .compile
      .toList
      .map(_.head)
}
