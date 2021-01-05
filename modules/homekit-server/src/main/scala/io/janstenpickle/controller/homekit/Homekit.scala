package io.janstenpickle.controller.homekit

import java.net.InetAddress
import java.nio.file.{Path, Paths}

import cats.{~>, Id}
import cats.effect.{ExitCode, IO, IOApp}
import extruder.core.Parser
import extruder.refined._
import extruder.typesafe._
import fs2.Stream
import io.janstenpickle.controller.server.Server
import org.http4s.HttpRoutes

import scala.concurrent.Future
import scala.util.Try

object Homekit extends IOApp {

  private val fkFuture: IO ~> Future = λ[IO ~> Future](_.unsafeToFuture())
  private val fk: IO ~> Id = λ[IO ~> Id](_.unsafeRunSync())

  implicit val inetAddressParser: Parser[InetAddress] = Parser.fromTry(addr => Try(InetAddress.getByName(addr)))
  implicit val pathParser: Parser[Path] = Parser.fromTry(path => Try(Paths.get(path)))

  override def run(args: List[String]): IO[ExitCode] =
    Server[IO, Configuration.Config](
      args.headOption,
      (config, signal) =>
        Stream.resource(Module.components[IO](config)).map {
          case (reg, homekit) => (HttpRoutes.empty[IO], reg, Some(homekit.run((fkFuture, fk, signal)).map(_ => ())))
      }
    ).compile.toList
      .map(_.head)
}
