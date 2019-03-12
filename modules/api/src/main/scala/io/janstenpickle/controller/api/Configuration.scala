package io.janstenpickle.controller.api

import java.nio.file.{Path, Paths}

import cats.effect.Sync
import com.github.mob41.blapi.mac.Mac
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined._
import eu.timepit.refined.types.net.PortNumber
import extruder.core.{ExtruderErrors, Parser}
import extruder.typesafe._
import extruder.refined._
import io.janstenpickle.controller.configsource.extruder._
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.remote.rm2.{RmRemote, RmRemoteConfig}
import io.janstenpickle.controller.sonos.SonosComponents
import io.janstenpickle.controller.store.file.{
  FileActivityStore,
  FileMacroStore,
  FileRemoteCommandStore,
  FileSwitchStateStore
}
import io.janstenpickle.controller.switch.hs100.HS100SmartPlug
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.virtual.SwitchesForRemote

import scala.concurrent.duration._
import scala.util.Try

object Configuration {
  case class Config(
    rm2: List[Rm],
    stores: Stores,
    virtualSwitch: SwitchesForRemote.PollingConfig,
    hs100: HS100,
    sonos: SonosComponents.Config,
    config: ConfigData,
    server: Server,
    activity: Activity
  )

  case class Rm(config: RmRemoteConfig, dependentSwitch: Option[SwitchKey])

  case class Activity(dependentSwitches: Map[Room, SwitchKey] = Map.empty)

  case class Stores(
    activityStore: FileActivityStore.Config,
    macroStore: FileMacroStore.Config,
    remoteCommandStore: FileRemoteCommandStore.Config,
    switchStateStore: FileSwitchStateStore.Config,
    switchStatePolling: FileSwitchStateStore.PollingConfig
  )

  case class ConfigData(
    file: Path,
    pollInterval: FiniteDuration = 10.seconds,
    activity: ExtruderActivityConfigSource.PollingConfig,
    button: ExtruderButtonConfigSource.PollingConfig,
    remote: ExtruderRemoteConfigSource.PollingConfig
  )

  case class HS100(configs: List[HS100SmartPlug.Config], polling: HS100SmartPlug.PollingConfig)

  case class Server(host: NonEmptyString = refineMV("0.0.0.0"), port: PortNumber = refineMV(8090))

  implicit val pathParser: Parser[Path] = Parser.fromTry(path => Try(Paths.get(path)))
  implicit val macParser: Parser[Mac] = Parser.fromTry(mac => Try(new Mac(mac)))

  def load[F[_]: Sync: ExtruderErrors]: F[Config] =
    decodeF[F, Config]()
}
