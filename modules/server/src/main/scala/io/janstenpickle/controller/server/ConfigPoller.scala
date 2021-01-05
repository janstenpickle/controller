package io.janstenpickle.controller.server

import java.nio.file.Paths

import cats.Eq
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.instances.all._
import cats.syntax.flatMap._
import com.typesafe.config.{Config, ConfigFactory}
import eu.timepit.refined.types.numeric.PosInt
import extruder.cats.effect.EffectValidation
import extruder.core.{Decoder, ExtruderErrors, Settings}
import extruder.data.ValidationErrors
import extruder.typesafe._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.poller.{DataPoller, Empty}

import scala.concurrent.duration._

object ConfigPoller {

  def apply[F[_]: ContextShift: Timer: ExtruderErrors, A: Eq](
    config: Option[String],
    blocker: Blocker,
    onUpdate: (Either[ValidationErrors, A], Either[ValidationErrors, A]) => F[Unit]
  )(
    implicit F: Concurrent[F],
    decoder: Decoder[EffectValidation[F, *], Settings, A, Config]
  ): Resource[F, () => F[Either[ValidationErrors, A]]] = {

    type ConfigResult[T] = EffectValidation[F, T]

    val configFile = config.map(Paths.get(_).toAbsolutePath.toFile)

    def load: F[Either[ValidationErrors, A]] =
      blocker
        .delay {
          val tsConfig = ConfigFactory.load()
          configFile.fold(tsConfig)(f => ConfigFactory.load(ConfigFactory.parseFile(f)).withFallback(tsConfig))
        }
        .flatMap(decodeF[ConfigResult, A](_).value)

    for {
      initial <- Resource.liftF(load)
      implicit0(logger: Logger[F]) <- Resource.liftF(Slf4jLogger.fromName("serverConfigPoller"))
      getConfig <- {
        implicit val empty: Empty[Either[ValidationErrors, A]] = Empty(initial)

        DataPoller[F, Either[ValidationErrors, A], () => F[Either[ValidationErrors, A]]](
          (_: Data[Either[ValidationErrors, A]]) => load,
          30.seconds,
          PosInt(1),
          onUpdate
        ) { (get, _) =>
          get
        }
      }
    } yield getConfig
  }
}
