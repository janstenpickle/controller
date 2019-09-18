package io.janstenpickle.controller.configsource.extruder

import cats.effect._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.string.NonEmptyString
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.typesafe.instances._
import extruder.circe.instances._
import extruder.core.{Decoder, Encoder, Settings}
import extruder.data.Validation
import extruder.refined._
import extruder.typesafe.IntermediateTypes.Config
import io.circe.Json
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Activities
import natchez.Trace

object ExtruderActivityConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: Activities => F[Unit]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, Activities, NonEmptyString]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, (Settings, CirceSettings), Activities, (TConfig, Json)] =
      Decoder[EV, (Settings, CirceSettings), Activities, (TConfig, Json)]

    val encoder: Encoder[F, Settings, Activities, Config] = Encoder[F, Settings, Activities, Config]

    ExtruderConfigSource
      .polling[F, G, Activities, NonEmptyString](
        "activities",
        pollingConfig,
        config,
        onUpdate,
        (key, activities) => activities.copy(activities = activities.activities.filterNot(_.name == key)),
        decoder,
        encoder
      )
  }
}
