package io.janstenpickle.controller.configsource.extruder

import cats.effect._
import com.typesafe.config.Config
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.typesafe.instances._
import extruder.circe.instances._
import extruder.core.{Decoder, Settings}
import extruder.refined._
import io.circe.Json
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Activities
import natchez.Trace

object ExtruderActivityConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: Activities => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, ConfigSource[F, Activities]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, (Settings, CirceSettings), Activities, (Config, Json)] =
      Decoder[EV, (Settings, CirceSettings), Activities, (Config, Json)]

    ExtruderConfigSource
      .polling[F, G, Activities]("activities", pollingConfig, config, onUpdate, decoder)
  }
}
