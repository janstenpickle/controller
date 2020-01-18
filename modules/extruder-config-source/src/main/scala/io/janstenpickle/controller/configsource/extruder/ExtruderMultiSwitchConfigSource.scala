package io.janstenpickle.controller.configsource.extruder

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.string._
import cats.syntax.flatMap._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.typesafe.instances._
import extruder.circe.instances._
import extruder.core.{Decoder, Encoder, Parser, Settings}
import extruder.refined._
import extruder.typesafe.IntermediateTypes.Config
import io.circe.{Json, Decoder => CirceDecoder}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.{ConfigEvent, SwitchEvent}
import io.janstenpickle.controller.model.event.ConfigEvent.{MultiSwitchAddedEvent, MultiSwitchRemovedEvent}
import io.janstenpickle.controller.model.{MultiSwitch, SwitchAction}
import natchez.Trace

object ExtruderMultiSwitchConfigSource {
  implicit val switchActionParser: Parser[SwitchAction] = Parser(SwitchAction.fromString(_))

  implicit val switchActionCirceDecoder: CirceDecoder[SwitchAction] =
    CirceDecoder.decodeString.emap(SwitchAction.fromString)

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    configEventPublisher: EventPublisher[F, ConfigEvent]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, NonEmptyString, MultiSwitch]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, Settings, ConfigResult[NonEmptyString, MultiSwitch], TConfig] =
      Decoder[EV, Settings, ConfigResult[NonEmptyString, MultiSwitch], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[NonEmptyString, MultiSwitch], Config] =
      Encoder[F, Settings, ConfigResult[NonEmptyString, MultiSwitch], Config]

    ExtruderConfigSource
      .polling[F, G, NonEmptyString, MultiSwitch](
        "multiSwitches",
        pollingConfig,
        config,
        Events.fromDiffValues(
          configEventPublisher,
          MultiSwitchAddedEvent(_, eventSource),
          m => MultiSwitchRemovedEvent(m.name, eventSource)
        ),
        decoder,
        encoder
      )
  }
}
