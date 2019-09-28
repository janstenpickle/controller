package io.janstenpickle.controller.configsource.extruder

import cats.effect._
import cats.instances.string._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import extruder.cats.effect.EffectValidation
import extruder.typesafe.instances._
import extruder.core.{Decoder, Encoder, Settings}
import extruder.data.Validation
import extruder.refined._
import extruder.typesafe.IntermediateTypes.Config
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Activity
import natchez.Trace

object ExtruderActivityConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: ConfigResult[String, Activity] => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, WritableConfigSource[F, String, Activity]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, Settings, ConfigResult[String, Activity], TConfig] =
      Decoder[EV, Settings, ConfigResult[String, Activity], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[String, Activity], Config] =
      Encoder[F, Settings, ConfigResult[String, Activity], Config]

    ExtruderConfigSource
      .polling[F, G, String, Activity]("activities", pollingConfig, config, onUpdate, decoder, encoder)
  }
}
