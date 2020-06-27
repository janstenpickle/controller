package io.janstenpickle.controller.broadlink

import java.net.InetAddress
import java.nio.file.Path

import cats.Eq
import eu.timepit.refined.cats._
import cats.instances.all._
import com.github.mob41.blapi.mac.Mac
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.remotecontrol.git.GithubRemoteCommandConfigSource
import io.janstenpickle.controller.server.{Server, ServerConfig}

import scala.concurrent.duration._

object Configuration {
  case class Config(
    coordinator: Option[Coordinator],
    host: Option[NonEmptyString],
    broadlink: BroadlinkComponents.Config,
    server: Server.Config,
    dir: Path,
    githubRemoteCommands: GithubRemoteCommandConfigSource.Config,
    polling: PollingConfig,
    writeTimeout: FiniteDuration = 10.seconds
  ) extends ServerConfig

  object Config {
    implicit val pathEq: Eq[Path] = Eq.by(_.toString)
    implicit val inetAddressEq: Eq[InetAddress] = Eq.by(_.getHostAddress)
    implicit val macEq: Eq[Mac] = Eq.by(_.getMacString)
    implicit val eq: Eq[Config] = cats.derived.semi.eq[Config]
  }

  case class Coordinator(host: NonEmptyString, port: PortNumber)
}
