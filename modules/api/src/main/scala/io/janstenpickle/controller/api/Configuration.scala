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
import io.janstenpickle.controller.remote.rm2.Rm2Remote
import io.janstenpickle.controller.store.file.{FileActivityStore, FileMacroStore, FileRemoteCommandStore}
import io.janstenpickle.controller.switch.hs100.HS100SmartPlug

import scala.concurrent.duration._
import scala.util.Try

object Configuration {
  case class Config(
    rm2: Rm2Remote.Config,
    activityStore: FileActivityStore.Config,
    macroStore: FileMacroStore.Config,
    remoteCommandStore: FileRemoteCommandStore.Config,
    hs100: HS100,
    config: ConfigData,
    server: Server
  )

  case class ConfigData(
    file: Path,
    pollInterval: FiniteDuration = 10.seconds,
    activity: ExtruderActivityConfigSource.PollingConfig,
    button: ExtruderButtonConfigSource.PollingConfig,
    remote: ExtruderRemoteConfigSource.PollingConfig
  )

  case class HS100(config: HS100SmartPlug.Config, polling: HS100SmartPlug.PollingConfig)

  case class Server(host: NonEmptyString = refineMV("0.0.0.0"), port: PortNumber = refineMV(8080))

  implicit val pathParser: Parser[Path] = Parser.fromTry(path => Try(Paths.get(path)))
  implicit val macParser: Parser[Mac] = Parser.fromTry(mac => Try(new Mac(mac)))

  def load[F[_]: Sync: ExtruderErrors]: F[Config] =
    decodeF[F, Config]()
}
