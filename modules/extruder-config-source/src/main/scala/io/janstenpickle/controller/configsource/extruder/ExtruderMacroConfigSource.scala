package io.janstenpickle.controller.configsource.extruder

import cats.data.NonEmptyList
import cats.effect._
import cats.instances.string._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import extruder.cats.effect.EffectValidation
import extruder.core.{Decoder, Encoder, Settings}
import extruder.refined._
import extruder.typesafe.instances._
import extruder.typesafe.IntermediateTypes.Config
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.model.event.ConfigEvent
import io.janstenpickle.controller.model.event.ConfigEvent.{MacroAddedEvent, MacroRemovedEvent}
import natchez.Trace

object ExtruderMacroConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    configEventPublisher: EventPublisher[F, ConfigEvent]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, NonEmptyString, NonEmptyList[Command]]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, Settings, ConfigResult[NonEmptyString, NonEmptyList[Command]], TConfig] =
      Decoder[EV, Settings, ConfigResult[NonEmptyString, NonEmptyList[Command]], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[NonEmptyString, NonEmptyList[Command]], Config] =
      Encoder[F, Settings, ConfigResult[NonEmptyString, NonEmptyList[Command]], Config]

    ExtruderConfigSource
      .polling[F, G, NonEmptyString, NonEmptyList[Command]](
        "macros",
        pollingConfig,
        config,
        Events.fromDiff(
          configEventPublisher,
          (name, commands) => MacroAddedEvent(name, commands, eventSource),
          (name, _) => MacroRemovedEvent(name, eventSource)
        ),
        decoder,
        encoder
      )
  }
}
