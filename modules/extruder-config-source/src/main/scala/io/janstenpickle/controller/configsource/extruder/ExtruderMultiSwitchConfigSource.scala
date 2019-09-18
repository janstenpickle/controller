package io.janstenpickle.controller.configsource.extruder

import cats.effect.{Concurrent, Resource, Sync, Timer}
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.string.NonEmptyString
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.typesafe.instances._
import extruder.circe.instances._
import extruder.core.{Decoder, Encoder, Parser, Settings}
import extruder.refined._
import extruder.typesafe.IntermediateTypes.Config
import io.circe.{Json, Decoder => CirceDecoder}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.{MultiSwitches, SwitchAction}
import io.janstenpickle.controller.poller.Empty
import natchez.Trace

object ExtruderMultiSwitchConfigSource {
  implicit val empty: Empty[MultiSwitches] = Empty(MultiSwitches(List.empty, List.empty))

  implicit val switchActionParser: Parser[SwitchAction] = Parser(SwitchAction.fromString(_))

  implicit val switchActionCirceDecoder: CirceDecoder[SwitchAction] =
    CirceDecoder.decodeString.emap(SwitchAction.fromString)

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: MultiSwitches => F[Unit]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, MultiSwitches, NonEmptyString]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, (Settings, CirceSettings), MultiSwitches, (TConfig, Json)] =
      Decoder[EV, (Settings, CirceSettings), MultiSwitches, (TConfig, Json)]
    val encoder: Encoder[F, Settings, MultiSwitches, Config] = Encoder[F, Settings, MultiSwitches, Config]

    ExtruderConfigSource
      .polling[F, G, MultiSwitches, NonEmptyString](
        "multiSwitches",
        pollingConfig,
        config,
        onUpdate,
        (key, switches) => switches.copy(multiSwitches = switches.multiSwitches.filterNot(_.name == key)),
        decoder,
        encoder
      )
  }
}
