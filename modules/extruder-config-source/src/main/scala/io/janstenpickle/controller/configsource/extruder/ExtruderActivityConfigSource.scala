package io.janstenpickle.controller.configsource.extruder

import cats.effect._
import cats.Eq
import com.typesafe.config.Config
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.typesafe.instances._
import extruder.circe.yaml.instances._
import extruder.core.{Decoder, Settings}
import extruder.refined._
import io.circe.Json
import io.janstenpickle.controller.configsource.ActivityConfigSource
import io.janstenpickle.controller.model.Activities
import io.janstenpickle.controller.poller.Empty

import scala.concurrent.duration._

object ExtruderActivityConfigSource {
  implicit val empty: Empty[Activities] = Empty(Activities(List.empty, List.empty))

  case class PollingConfig(pollInterval: FiniteDuration = 10.seconds)

  def apply[F[_]: Sync](config: ConfigFileSource[F]): ActivityConfigSource[F] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, ((Settings, CirceSettings), CirceSettings), Activities, ((Config, Json), Json)] =
      Decoder[EV, ((Settings, CirceSettings), CirceSettings), Activities, ((Config, Json), Json)]

    val source = ExtruderConfigSource[F, Activities](
      config,
      (current, error) => current.copy(errors = error.fold(List.empty[String])(th => List(th.getMessage))),
      (current, errors) => current.copy(errors = errors.toList.map(_.message))
    )(Sync[F], decoder, empty)

    new ActivityConfigSource[F] {
      override def getActivities: F[Activities] = source()
    }
  }

  def polling[F[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: Activities => F[Unit]
  ): Resource[F, ActivityConfigSource[F]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, ((Settings, CirceSettings), CirceSettings), Activities, ((Config, Json), Json)] =
      Decoder[EV, ((Settings, CirceSettings), CirceSettings), Activities, ((Config, Json), Json)]

    ExtruderConfigSource
      .polling[F, Activities](
        pollingConfig.pollInterval,
        config,
        (current, error) => current.copy(errors = error.fold(List.empty[String])(th => List(th.getMessage))),
        (current, errors) => current.copy(errors = errors.toList.map(_.message)),
        onUpdate
      )(Timer[F], empty, Eq[Activities], Concurrent[F], decoder)
      .map { source =>
        new ActivityConfigSource[F] {
          override def getActivities: F[Activities] = source()
        }
      }
  }
}
