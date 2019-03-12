package io.janstenpickle.controller.configsource.extruder

import cats.effect._
import cats.Eq
import com.typesafe.config.Config
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.typesafe.instances._
import extruder.circe.instances._
import extruder.core.{Decoder, Settings}
import extruder.refined._
import io.circe.Json
import io.janstenpickle.controller.configsource.ActivityConfigSource
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Activities
import io.janstenpickle.controller.poller.Empty

import scala.concurrent.duration._

object ExtruderActivityConfigSource {
  implicit val empty: Empty[Activities] = Empty(Activities(List.empty, List.empty))

  case class PollingConfig(pollInterval: FiniteDuration = 10.seconds)

  private def mkSource[F[_]](source: () => F[Activities]): ActivityConfigSource[F] =
    new ActivityConfigSource[F] {
      override def getActivities: F[Activities] = source()
    }

  def apply[F[_]: Sync](config: ConfigFileSource[F]): ActivityConfigSource[F] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, (Settings, CirceSettings), Activities, (Config, Json)] =
      Decoder[EV, (Settings, CirceSettings), Activities, (Config, Json)]

    val source = ExtruderConfigSource[F, Activities](
      config,
      (current, error) => current.copy(errors = error.fold(List.empty[String])(th => List(th.getMessage))),
      (current, errors) => current.copy(errors = errors.toList.map(_.message))
    )(Sync[F], decoder, empty)

    mkSource[F](source)
  }

  def polling[F[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: Activities => F[Unit]
  ): Resource[F, ActivityConfigSource[F]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, (Settings, CirceSettings), Activities, (Config, Json)] =
      Decoder[EV, (Settings, CirceSettings), Activities, (Config, Json)]

    ExtruderConfigSource
      .polling[F, Activities](
        pollingConfig.pollInterval,
        config,
        (current, error) => current.copy(errors = error.fold(List.empty[String])(th => List(th.getMessage))),
        (current, errors) => current.copy(errors = errors.toList.map(_.message)),
        onUpdate
      )(Timer[F], empty, Eq[Activities], Concurrent[F], decoder)
      .map(mkSource[F])
  }
}
