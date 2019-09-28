package io.janstenpickle.controller.configsource.extruder

import cats.effect._
import cats.instances.string._
import com.typesafe.config.{Config => TConfig}
import extruder.cats.effect.EffectValidation
import extruder.core.{Decoder, Encoder, Settings}
import extruder.typesafe.instances._
import extruder.refined._
import extruder.typesafe.IntermediateTypes.Config
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Button
import natchez.Trace

object ExtruderButtonConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: ConfigResult[String, Button] => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, WritableConfigSource[F, String, Button]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, Settings, ConfigResult[String, Button], TConfig] =
      Decoder[EV, Settings, ConfigResult[String, Button], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[String, Button], Config] =
      Encoder[F, Settings, ConfigResult[String, Button], Config]

    ExtruderConfigSource
      .polling[F, G, String, Button]("buttons", pollingConfig, config, onUpdate, decoder, encoder)
  }
}
