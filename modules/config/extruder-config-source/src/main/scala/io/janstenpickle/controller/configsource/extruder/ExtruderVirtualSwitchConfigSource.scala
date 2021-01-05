package io.janstenpickle.controller.configsource.extruder

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.string._
import cats.syntax.flatMap._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.typesafe.instances._
import extruder.circe.instances._
import extruder.core.{Decoder, Encoder, Parser, Settings, Show}
import extruder.refined._
import extruder.typesafe.IntermediateTypes.Config
import io.circe.Json
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.{ConfigEvent, SwitchEvent}
import io.janstenpickle.controller.model.event.ConfigEvent.{VirtualSwitchAddedEvent, VirtualSwitchRemovedEvent}
import io.janstenpickle.controller.model.event.SwitchEvent.{SwitchAddedEvent, SwitchRemovedEvent}
import io.janstenpickle.controller.model.{RemoteSwitchKey, State, SwitchMetadata, SwitchType, VirtualSwitch}
import natchez.Trace

object ExtruderVirtualSwitchConfigSource {
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

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    configEventPublisher: EventPublisher[F, ConfigEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, RemoteSwitchKey, VirtualSwitch]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, Settings, ConfigResult[RemoteSwitchKey, VirtualSwitch], TConfig] =
      Decoder[EV, Settings, ConfigResult[RemoteSwitchKey, VirtualSwitch], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[RemoteSwitchKey, VirtualSwitch], Config] =
      Encoder[F, Settings, ConfigResult[RemoteSwitchKey, VirtualSwitch], Config]

    ExtruderConfigSource
      .polling[F, G, RemoteSwitchKey, VirtualSwitch](
        "virtualSwitches",
        pollingConfig,
        config,
        diff =>
          Events.fromDiff[F, SwitchEvent, RemoteSwitchKey, VirtualSwitch](
            switchEventPublisher,
            (k: RemoteSwitchKey, virtual: VirtualSwitch) =>
              SwitchAddedEvent(
                k.toSwitchKey,
                SwitchMetadata(room = virtual.room.map(_.value), `type` = SwitchType.Virtual)
            ),
            (k: RemoteSwitchKey, _: VirtualSwitch) => SwitchRemovedEvent(k.toSwitchKey)
          )(diff) >> Events.fromDiff[F, ConfigEvent, RemoteSwitchKey, VirtualSwitch](
            configEventPublisher,
            (k: RemoteSwitchKey, virtual: VirtualSwitch) => VirtualSwitchAddedEvent(k, virtual, eventSource),
            (k: RemoteSwitchKey, _: VirtualSwitch) => VirtualSwitchRemovedEvent(k, eventSource)
          )(diff),
        decoder,
        encoder
      )
  }
}
