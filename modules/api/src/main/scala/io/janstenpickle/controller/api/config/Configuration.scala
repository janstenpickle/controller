package io.janstenpickle.controller.api.config

import java.io.File
import java.net.InetAddress
import java.nio.file.{Path, Paths}

import cats.effect.{Blocker, ContextShift, Sync}
import cats.syntax.flatMap._
import com.github.mob41.blapi.mac.Mac
import com.typesafe.config.ConfigFactory
import eu.timepit.refined.refineMV
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import extruder.core.{ExtruderErrors, Parser}
import extruder.typesafe._
import extruder.refined._
import io.janstenpickle.controller.broadlink.BroadlinkComponents
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource
import io.janstenpickle.controller.events.kafka.KafkaPubSub
import io.janstenpickle.controller.events.mqtt.MqttEvents
import io.janstenpickle.controller.homekit.ControllerHomekitServer
import io.janstenpickle.controller.kodi.KodiComponents
import io.janstenpickle.controller.model.{Room, SwitchKey}
import io.janstenpickle.controller.mqtt.Fs2MqttClient
import io.janstenpickle.controller.remotecontrol.git.GithubRemoteCommandConfigSource
import io.janstenpickle.controller.sonos.SonosComponents
import io.janstenpickle.controller.switch.virtual.SwitchesForRemote
import io.janstenpickle.controller.tplink.TplinkComponents
import io.janstenpickle.deconz.DeconzBridge

import scala.concurrent.duration._
import scala.util.Try

object Configuration {
  case class Config(
    broadlink: BroadlinkComponents.Config,
    virtualSwitch: VirtualSwitch,
    tplink: TplinkComponents.Config,
    sonos: SonosComponents.Config,
    kodi: KodiComponents.Config,
    config: ConfigData,
    server: Server,
    activity: Activity,
    githubRemoteCommands: GithubRemoteCommandConfigSource.Config,
    homekit: ControllerHomekitServer.Config,
    deconz: Option[DeconzBridge.Config],
    mqtt: Mqtt,
    kafka: Option[KafkaPubSub.Config]
  )

  case class Mqtt(client: Option[Fs2MqttClient.Config], events: MqttEvents.Config)

  case class Activity(dependentSwitches: Map[Room, SwitchKey] = Map.empty)

  case class ConfigData(
    dir: Path = Paths.get("/", "tmp", "controller", "config"),
    writeTimeout: FiniteDuration = 1.seconds,
    polling: ExtruderConfigSource.PollingConfig
  )

  case class VirtualSwitch(
    dependentSwitches: Map[NonEmptyString, SwitchKey] = Map.empty,
    polling: SwitchesForRemote.PollingConfig
  )

  case class Server(
    host: NonEmptyString = refineMV("0.0.0.0"),
    port: PortNumber = refineMV(8090),
    responseHeaderTimeout: FiniteDuration = 4.seconds,
    idleTimeout: FiniteDuration = 5.seconds
  )

  implicit val pathParser: Parser[Path] = Parser.fromTry(path => Try(Paths.get(path)))
  implicit val macParser: Parser[Mac] = Parser.fromTry(mac => Try(new Mac(mac)))
  implicit val inetAddressParser: Parser[InetAddress] = Parser.fromTry(addr => Try(InetAddress.getByName(addr)))

  def load[F[_]: Sync: ContextShift: ExtruderErrors](blocker: Blocker, config: Option[File] = None): F[Config] =
    blocker
      .delay {
        val tsConfig = ConfigFactory.load()
        config.fold(tsConfig)(f => ConfigFactory.load(ConfigFactory.parseFile(f)).withFallback(tsConfig))
      }
      .flatMap(decodeF[F, Config](_))
}
