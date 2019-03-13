package io.janstenpickle.controller.configsource.extruder

import cats.Eq
import cats.effect.{Concurrent, Resource, Sync, Timer}
import com.typesafe.config.Config
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.typesafe.instances._
import extruder.circe.instances._
import extruder.core.{Decoder, Settings}
import extruder.refined._
import io.circe.Json
import io.janstenpickle.controller.configsource.VirtualSwitchConfigSource
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.VirtualSwitches
import io.janstenpickle.controller.poller.Empty

import scala.concurrent.duration._

object ExtruderVirtualSwitchConfigSource {
  implicit val empty: Empty[VirtualSwitches] = Empty(VirtualSwitches(List.empty, List.empty))

  case class PollingConfig(pollInterval: FiniteDuration = 10.seconds)

  private def mkSource[F[_]](source: () => F[VirtualSwitches]): VirtualSwitchConfigSource[F] =
    new VirtualSwitchConfigSource[F] {
      override def getVirtualSwitches: F[VirtualSwitches] = source()
    }

  def apply[F[_]: Sync](config: ConfigFileSource[F]): VirtualSwitchConfigSource[F] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, (Settings, CirceSettings), VirtualSwitches, (Config, Json)] =
      Decoder[EV, (Settings, CirceSettings), VirtualSwitches, (Config, Json)]

    val source = ExtruderConfigSource[F, VirtualSwitches](
      config,
      (current, error) => current.copy(errors = error.fold(List.empty[String])(th => List(th.getMessage))),
      (current, errors) => current.copy(errors = errors.toList.map(_.message))
    )(Sync[F], decoder, empty)

    mkSource[F](source)
  }

  def polling[F[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: VirtualSwitches => F[Unit]
  ): Resource[F, VirtualSwitchConfigSource[F]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, (Settings, CirceSettings), VirtualSwitches, (Config, Json)] =
      Decoder[EV, (Settings, CirceSettings), VirtualSwitches, (Config, Json)]

    ExtruderConfigSource
      .polling[F, VirtualSwitches](
        pollingConfig.pollInterval,
        config,
        (current, error) => current.copy(errors = error.fold(List.empty[String])(th => List(th.getMessage))),
        (current, errors) => current.copy(errors = errors.toList.map(_.message)),
        onUpdate
      )(Timer[F], empty, Eq[VirtualSwitches], Concurrent[F], decoder)
      .map(mkSource[F])
  }
}
