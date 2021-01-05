package io.janstenpickle.controller.kodi

import java.net.InetAddress
import java.nio.file.Path

import cats.Eq
import eu.timepit.refined.cats._
import cats.instances.all._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.server.{Server, ServerConfig}

import scala.concurrent.duration._

object Configuration {
  case class Config(
    coordinator: Option[Coordinator],
    host: Option[NonEmptyString],
    kodi: KodiComponents.Config,
    server: Server.Config,
    dir: Path,
    polling: PollingConfig,
    writeTimeout: FiniteDuration = 10.seconds
  ) extends ServerConfig

  object Config {
    implicit val pathEq: Eq[Path] = Eq.by(_.toString)
    implicit val inetAddressEq: Eq[InetAddress] = Eq.by(_.getHostAddress)
    implicit val eq: Eq[Config] = cats.derived.semi.eq[Config]
  }

  case class Coordinator(host: NonEmptyString, port: PortNumber)
}
