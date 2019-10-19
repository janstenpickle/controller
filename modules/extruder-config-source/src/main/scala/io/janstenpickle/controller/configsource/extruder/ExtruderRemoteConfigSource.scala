package io.janstenpickle.controller.configsource.extruder

import cats.effect._
import cats.instances.string._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import io.janstenpickle.controller.model.Remote
import extruder.typesafe.instances._
import extruder.circe.instances._
import extruder.core.{Decoder, Encoder, Settings}
import extruder.refined._
import extruder.typesafe.IntermediateTypes.Config
import io.circe.Json
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.extruder.ConfigFileSource
import natchez.Trace

object ExtruderRemoteConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: ConfigResult[NonEmptyString, Remote] => F[Unit]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, NonEmptyString, Remote]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, Settings, ConfigResult[NonEmptyString, Remote], TConfig] =
      Decoder[EV, Settings, ConfigResult[NonEmptyString, Remote], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[NonEmptyString, Remote], Config] =
      Encoder[F, Settings, ConfigResult[NonEmptyString, Remote], Config]

    ExtruderConfigSource
      .polling[F, G, NonEmptyString, Remote]("remotes", pollingConfig, config, onUpdate, decoder, encoder)
  }
}
