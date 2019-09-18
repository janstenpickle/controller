package io.janstenpickle.controller.configsource.extruder

import cats.effect._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.string.NonEmptyString
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.typesafe.instances._
import extruder.circe.instances._
import extruder.core.{Decoder, Encoder, Settings}
import extruder.refined._
import extruder.typesafe.IntermediateTypes.Config
import io.circe.Json
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Buttons
import natchez.Trace

object ExtruderButtonConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: Buttons => F[Unit]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, Buttons, NonEmptyString]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, (Settings, CirceSettings), Buttons, (TConfig, Json)] =
      Decoder[EV, (Settings, CirceSettings), Buttons, (TConfig, Json)]
    val encoder: Encoder[F, Settings, Buttons, Config] = Encoder[F, Settings, Buttons, Config]

    ExtruderConfigSource
      .polling[F, G, Buttons, NonEmptyString](
        "buttons",
        pollingConfig,
        config,
        onUpdate,
        (key, buttons) => buttons.copy(buttons = buttons.buttons.filterNot(_.name == key)),
        decoder,
        encoder
      )
  }
}
