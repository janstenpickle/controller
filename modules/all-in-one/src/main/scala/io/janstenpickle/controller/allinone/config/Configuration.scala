package io.janstenpickle.controller.allinone.config

import java.net.InetAddress
import java.nio.file.{Path, Paths}

import cats.Eq
import cats.instances.all._
import com.github.mob41.blapi.mac.Mac
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import io.janstenpickle.controller.broadlink.BroadlinkComponents
import io.janstenpickle.controller.configsource.circe.CirceConfigSource
import io.janstenpickle.controller.deconz.DeconzBridge
import io.janstenpickle.controller.events.kafka.KafkaPubSub
import io.janstenpickle.controller.events.mqtt.MqttEvents
import io.janstenpickle.controller.kodi.KodiComponents
import io.janstenpickle.controller.model.{Room, SwitchKey}
import io.janstenpickle.controller.mqtt.Fs2MqttClient
import io.janstenpickle.controller.remotecontrol.git.GithubRemoteCommandConfigSource
import io.janstenpickle.controller.server.{Server, ServerConfig}
import io.janstenpickle.controller.sonos.SonosComponents
import io.janstenpickle.controller.switch.virtual.SwitchesForRemote
import io.janstenpickle.controller.tplink.TplinkComponents

import scala.concurrent.duration._

object Configuration {
  case class Config(
    hostname: Option[NonEmptyString],
    broadlink: BroadlinkComponents.Config,
    virtualSwitch: VirtualSwitch,
    tplink: TplinkComponents.Config,
    sonos: SonosComponents.Config,
    kodi: KodiComponents.Config,
    config: ConfigData,
    server: Server.Config,
    activity: Activity,
    githubRemoteCommands: GithubRemoteCommandConfigSource.Config,
    deconz: Option[DeconzBridge.DeconzApiConfig],
    mqtt: Mqtt,
    kafka: Option[KafkaPubSub.Config]
  ) extends ServerConfig

  object Config {
    implicit val pathEq: Eq[Path] = Eq.by(_.toString)
    implicit val macEq: Eq[Mac] = Eq.by(_.getMacString)
    implicit val inetAddressEq: Eq[InetAddress] = Eq.by(_.getHostAddress)
    implicit val configEq: Eq[Configuration.Config] = cats.derived.semi.eq[Configuration.Config]
  }

  case class Mqtt(client: Option[Fs2MqttClient.Config], events: MqttEvents.Config)

  case class Activity(dependentSwitches: Map[Room, SwitchKey] = Map.empty)

  case class ConfigData(
    dir: Path = Paths.get("/", "tmp", "controller", "config"),
    writeTimeout: FiniteDuration = 1.seconds,
    polling: CirceConfigSource.PollingConfig
  )

  case class VirtualSwitch(
    dependentSwitches: Map[NonEmptyString, SwitchKey] = Map.empty,
    polling: SwitchesForRemote.PollingConfig
  )
}
