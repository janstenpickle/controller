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
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource
import io.janstenpickle.controller.remote.rm2.Rm2Remote
import io.janstenpickle.controller.store.file.FileStore
import io.janstenpickle.controller.switch.hs100.HS100SmartPlug

import scala.util.Try

object Configuration {
  case class Config(
    rm2: Rm2Remote.Config,
    fileStore: FileStore.Config,
    hs100: HS100SmartPlug.Config,
    data: ExtruderConfigSource.Config,
    server: Server
  )

  case class Server(host: NonEmptyString = refineMV("0.0.0.0"), port: PortNumber = refineMV(8080))

  implicit val pathParser: Parser[Path] = Parser.fromTry(path => Try(Paths.get(path)))
  implicit val macParser: Parser[Mac] = Parser.fromTry(mac => Try(new Mac(mac)))

  def load[F[_]: Sync: ExtruderErrors]: F[Config] =
    decodeF[F, Config]()
}
