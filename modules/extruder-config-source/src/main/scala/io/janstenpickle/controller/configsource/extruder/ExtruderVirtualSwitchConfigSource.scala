package io.janstenpickle.controller.configsource.extruder

import cats.effect.{Concurrent, Resource, Sync, Timer}
import com.typesafe.config.{Config => TConfig}
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.typesafe.instances._
import extruder.circe.instances._
import extruder.core.{Decoder, Encoder, Settings}
import extruder.refined._
import extruder.typesafe.IntermediateTypes.Config
import io.circe.Json
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.VirtualSwitches
import natchez.Trace

object ExtruderVirtualSwitchConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: VirtualSwitches => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, WritableConfigSource[F, VirtualSwitches]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, (Settings, CirceSettings), VirtualSwitches, (TConfig, Json)] =
      Decoder[EV, (Settings, CirceSettings), VirtualSwitches, (TConfig, Json)]
    val encoder: Encoder[F, Settings, VirtualSwitches, Config] = Encoder[F, Settings, VirtualSwitches, Config]

    ExtruderConfigSource
      .polling[F, G, VirtualSwitches]("virtualSwitches", pollingConfig, config, onUpdate, decoder, encoder)
  }
}
