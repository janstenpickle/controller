package io.janstenpickle.switches.config

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.syntax.flatMap._
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.circe.{eventSource, CirceConfigSource, Events}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.ConfigEvent.{VirtualSwitchAddedEvent, VirtualSwitchRemovedEvent}
import io.janstenpickle.controller.model.event.SwitchEvent.{SwitchAddedEvent, SwitchRemovedEvent}
import io.janstenpickle.controller.model.event.{ConfigEvent, SwitchEvent}
import io.janstenpickle.controller.model.{RemoteSwitchKey, SwitchMetadata, SwitchType, VirtualSwitch}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

object CirceVirtualSwitchConfigSource {

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    configEventPublisher: EventPublisher[F, ConfigEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[F, WritableConfigSource[F, RemoteSwitchKey, VirtualSwitch]] =
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
          )(diff),
        k
      )
}
