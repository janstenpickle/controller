package io.janstenpickle.controller.api.config

import java.io.File
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
import io.janstenpickle.controller.broadlink.remote.RmRemoteConfig
import io.janstenpickle.controller.broadlink.switch.{SpSwitch, SpSwitchConfig}
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.sonos.SonosComponents
import io.janstenpickle.controller.stats.StatsStream
import io.janstenpickle.controller.switch.hs100.HS100SmartPlug
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.virtual.SwitchesForRemote

import scala.concurrent.duration._
import scala.util.Try

object Configuration {
  case class Config(
    rm: List[Rm] = List.empty,
    virtualSwitch: SwitchesForRemote.PollingConfig,
    hs100: HS100,
    sp: Sp,
    sonos: SonosComponents.Config,
    config: ConfigData,
    server: Server,
    activity: Activity,
    stats: StatsStream.Config
  )

  case class Rm(config: RmRemoteConfig, dependentSwitch: Option[SwitchKey])

  case class Activity(dependentSwitches: Map[Room, SwitchKey] = Map.empty)

  case class ConfigData(
    dir: Path,
    writeTimeout: FiniteDuration = 1.seconds,
    polling: ExtruderConfigSource.PollingConfig
  )

  case class HS100(configs: List[HS100SmartPlug.Config] = List.empty, polling: HS100SmartPlug.PollingConfig)
  case class Sp(configs: List[SpSwitchConfig] = List.empty, polling: SpSwitch.PollingConfig)

  case class Server(host: NonEmptyString = refineMV("0.0.0.0"), port: PortNumber = refineMV(8090))

  implicit val pathParser: Parser[Path] = Parser.fromTry(path => Try(Paths.get(path)))
  implicit val macParser: Parser[Mac] = Parser.fromTry(mac => Try(new Mac(mac)))

  def load[F[_]: Sync: ContextShift: ExtruderErrors](blocker: Blocker, config: Option[File] = None): F[Config] =
    blocker
      .delay {
        val tsConfig = ConfigFactory.load()
        config.fold(tsConfig)(f => ConfigFactory.load(ConfigFactory.parseFile(f)).withFallback(tsConfig))
      }
      .flatMap(decodeF[F, Config](_))
}
