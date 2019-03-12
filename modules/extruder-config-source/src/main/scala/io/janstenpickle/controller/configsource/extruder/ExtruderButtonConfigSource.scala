package io.janstenpickle.controller.configsource.extruder

import cats.effect._
import cats.Eq
import com.typesafe.config.Config
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import io.janstenpickle.controller.model
import extruder.typesafe.instances._
import extruder.circe.yaml.instances._
import extruder.core.{Decoder, Settings}
import extruder.refined._
import io.circe.Json
import io.janstenpickle.controller.configsource.ButtonConfigSource
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Buttons
import io.janstenpickle.controller.poller.Empty

import scala.concurrent.duration._

object ExtruderButtonConfigSource {
  implicit val empty: Empty[Buttons] = Empty(Buttons(List.empty, List.empty))

  case class PollingConfig(pollInterval: FiniteDuration = 10.seconds)

  private def mkSource[F[_]](source: () => F[Buttons]): ButtonConfigSource[F] = new ButtonConfigSource[F] {
    override def getCommonButtons: F[Buttons] = source()
  }

  def apply[F[_]: Sync](config: ConfigFileSource[F]): ButtonConfigSource[F] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, (Settings, CirceSettings), Buttons, (Config, Json)] =
      Decoder[EV, (Settings, CirceSettings), Buttons, (Config, Json)]

    val source = ExtruderConfigSource[F, Buttons](
      config,
      (current, error) => current.copy(errors = error.fold(List.empty[String])(th => List(th.getMessage))),
      (current, errors) => current.copy(errors = errors.toList.map(_.message))
    )(Sync[F], decoder, empty)

    mkSource[F](source)
  }

  def polling[F[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: Buttons => F[Unit]
  ): Resource[F, ButtonConfigSource[F]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, (Settings, CirceSettings), Buttons, (Config, Json)] =
      Decoder[EV, (Settings, CirceSettings), Buttons, (Config, Json)]

    ExtruderConfigSource
      .polling[F, Buttons](
        pollingConfig.pollInterval,
        config,
        (current, error) => current.copy(errors = error.fold(List.empty[String])(th => List(th.getMessage))),
        (current, errors) => current.copy(errors = errors.toList.map(_.message)),
        onUpdate
      )(Timer[F], empty, Eq[Buttons], Concurrent[F], decoder)
      .map(mkSource[F])
  }
}
