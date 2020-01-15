package io.janstenpickle.deconz.config

import cats.effect._
import cats.instances.string._
import cats.instances.either._
import cats.instances.set._
import com.typesafe.config.{Config => TConfig}
import extruder.cats.effect.EffectValidation
import extruder.core.{Decoder, Encoder, Parser, Settings, Show}
import extruder.typesafe.instances._
import extruder.refined._
import extruder.typesafe.IntermediateTypes.Config
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.extruder.{ExtruderConfigSource, KeySeparator}
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.deconz.model.{ButtonAction, ButtonMappingKey}
import natchez.Trace

object ExtruderButtonMappingConfigSource {

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: ConfigResult[String, Set[ActionMapping]] => F[Unit]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, String, Set[ActionMapping]]] = {
    type EV[A] = EffectValidation[F, A]

    val decoder: Decoder[EV, Settings, ConfigResult[String, Set[ActionMapping]], TConfig] =
      Decoder[EV, Settings, ConfigResult[String, Set[ActionMapping]], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[String, Set[ActionMapping]], Config] =
      Encoder[F, Settings, ConfigResult[String, Set[ActionMapping]], Config]

    ExtruderConfigSource
      .polling[F, G, String, Set[ActionMapping]]("mapping", pollingConfig, config, onUpdate, decoder, encoder)
  }
}
