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
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.model.event.ActivityUpdateEvent
import natchez.Trace

object ExtruderCurrentActivityConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    activityUpdateEventPublisher: EventPublisher[F, ActivityUpdateEvent]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, Room, NonEmptyString]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, Settings, ConfigResult[Room, NonEmptyString], TConfig] =
      Decoder[EV, Settings, ConfigResult[Room, NonEmptyString], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[Room, NonEmptyString], Config] =
      Encoder[F, Settings, ConfigResult[Room, NonEmptyString], Config]

    ExtruderConfigSource
      .polling[F, G, NonEmptyString, NonEmptyString](
        "currentActivity",
        pollingConfig,
        config,
        Events.fromDiff(activityUpdateEventPublisher, ActivityUpdateEvent(_, _), ActivityUpdateEvent(_, _)),
        decoder,
        encoder
      )
  }
}
