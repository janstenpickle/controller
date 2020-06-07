package io.janstenpickle.controller.configsource.circe

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.syntax.flatMap._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.ConfigEvent.{VirtualSwitchAddedEvent, VirtualSwitchRemovedEvent}
import io.janstenpickle.controller.model.event.SwitchEvent.{SwitchAddedEvent, SwitchRemovedEvent}
import io.janstenpickle.controller.model.event.{ConfigEvent, SwitchEvent}
import io.janstenpickle.controller.model.{RemoteSwitchKey, SwitchMetadata, SwitchType, VirtualSwitch}
import natchez.Trace

object CirceVirtualSwitchConfigSource {

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    configEventPublisher: EventPublisher[F, ConfigEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, RemoteSwitchKey, VirtualSwitch]] =
    CirceConfigSource
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
          )(diff)
      )
}