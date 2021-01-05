package io.janstenpickle.controller.configsource.extruder

import cats.data.NonEmptyList
import cats.effect._
import cats.instances.string._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import extruder.cats.effect.EffectValidation
import extruder.core.{Decoder, Encoder, Parser, Settings, Show}
import extruder.refined._
import extruder.typesafe.instances._
import extruder.typesafe.IntermediateTypes.Config
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.model.{RemoteSwitchKey, State}
import natchez.Trace

object ExtruderSwitchStateConfigSource {
  implicit val switchKeyParser: Parser[RemoteSwitchKey] = Parser[String].flatMapResult { value =>
    value.split(KeySeparator).toList match {
      case remote :: device :: name :: Nil =>
        for {
          r <- refineV[NonEmpty](remote)
          d <- refineV[NonEmpty](device)
          n <- refineV[NonEmpty](name)
        } yield RemoteSwitchKey(r, d, n)
      case _ => Left(s"Invalid remote command value '$value'")
    }
  }
  implicit val switchKeyShow: Show[RemoteSwitchKey] = Show { sk =>
    s"${sk.remote}$KeySeparator${sk.device}$KeySeparator${sk.name}"
  }
  implicit val stateParser: Parser[State] = Parser[Boolean].map(State.fromBoolean)
  implicit val stateShow: Show[State] = Show[Boolean].contramap(_.isOn)

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    switchUpdatedPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, RemoteSwitchKey, State]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, Settings, ConfigResult[RemoteSwitchKey, State], TConfig] =
      Decoder[EV, Settings, ConfigResult[RemoteSwitchKey, State], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[RemoteSwitchKey, State], Config] =
      Encoder[F, Settings, ConfigResult[RemoteSwitchKey, State], Config]

    ExtruderConfigSource
      .polling[F, G, RemoteSwitchKey, State](
        "switchState",
        pollingConfig,
        config,
        Events.fromDiff(
          switchUpdatedPublisher,
          (rk, s) => SwitchStateUpdateEvent(rk.toSwitchKey, s),
          (rk, _) => SwitchStateUpdateEvent(rk.toSwitchKey, State.Off)
        ),
        decoder,
        encoder
      )
  }
}
