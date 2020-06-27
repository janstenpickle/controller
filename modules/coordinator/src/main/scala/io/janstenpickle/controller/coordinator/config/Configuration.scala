package io.janstenpickle.controller.coordinator.config

import java.nio.file.{Path, Paths}

import cats.Eq
import cats.instances.all._
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import io.janstenpickle.controller.configsource.circe.CirceConfigSource
import io.janstenpickle.controller.model.{Room, SwitchKey}
import io.janstenpickle.controller.server.{Server, ServerConfig}
import io.janstenpickle.controller.switch.virtual.SwitchesForRemote

import scala.concurrent.duration._

object Configuration {
  case class Config(
    hostname: Option[NonEmptyString],
    config: ConfigData,
    virtualSwitch: VirtualSwitch,
    activity: Activity,
    server: Server.Config,
    frontendPath: Option[Path] = Option(System.getenv("CONTROLLER_FRONTEND_PATH")).map(Paths.get(_))
  ) extends ServerConfig

  object Config {
    implicit val pathEq: Eq[Path] = Eq.by(_.toString)
    implicit val configEq: Eq[Configuration.Config] = cats.derived.semi.eq[Configuration.Config]
  }

  case class ConfigData(
    dir: Path = Paths.get("/", "tmp", "controller", "config"),
    writeTimeout: FiniteDuration = 1.seconds,
    polling: CirceConfigSource.PollingConfig
  )

  case class VirtualSwitch(
    dependentSwitches: Map[NonEmptyString, SwitchKey] = Map.empty,
    polling: SwitchesForRemote.PollingConfig
  )

  case class Activity(dependentSwitches: Map[Room, SwitchKey] = Map.empty)

}
