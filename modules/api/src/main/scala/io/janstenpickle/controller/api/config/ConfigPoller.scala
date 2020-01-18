package io.janstenpickle.controller.api.config

import java.net.InetAddress
import java.nio.file.{Path, Paths}
import java.util.concurrent.Executors

import cats.Eq
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import io.janstenpickle.controller.poller.{DataPoller, Empty}
import cats.instances.all._
import com.github.mob41.blapi.mac.Mac
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.PosInt
import extruder.cats.effect.EffectValidation
import extruder.core.ExtruderErrors
import extruder.data.ValidationErrors
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.poller.DataPoller.Data

import scala.concurrent.duration._

object ConfigPoller {
  implicit val pathEq: Eq[Path] = Eq.by(_.toString)
  implicit val macEq: Eq[Mac] = Eq.by(_.getMacString)
  implicit val inetAddressEq: Eq[InetAddress] = Eq.by(_.getHostAddress)
  implicit val configEq: Eq[Configuration.Config] = cats.derived.semi.eq[Configuration.Config]

  def apply[F[_]: ContextShift: Timer: ExtruderErrors](
    config: Option[String],
    onUpdate: (
      Either[ValidationErrors, Configuration.Config],
      Either[ValidationErrors, Configuration.Config]
    ) => F[Unit]
  )(implicit F: Concurrent[F]): Resource[F, () => F[Either[ValidationErrors, Configuration.Config]]] = {
    type ConfigResult[A] = EffectValidation[F, A]

    val configFile = config.map(Paths.get(_).toAbsolutePath.toFile)

    for {
      blocker <- Resource
        .make(F.delay(Executors.newCachedThreadPool()))(es => F.delay(es.shutdown()))
        .map(e => Blocker.liftExecutorService(e))
      initial <- Resource.liftF(Configuration.load[ConfigResult](blocker, configFile).value)
      implicit0(logger: Logger[F]) <- Resource.liftF(Slf4jLogger.fromName("mainApplicationConfigPoller"))
      getConfig <- {
        implicit val empty: Empty[Either[ValidationErrors, Configuration.Config]] = Empty(initial)

        DataPoller[F, Either[ValidationErrors, Configuration.Config], () => F[
          Either[ValidationErrors, Configuration.Config]
        ]](
          (_: Data[Either[ValidationErrors, Configuration.Config]]) =>
            Configuration.load[ConfigResult](blocker, configFile).value,
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
