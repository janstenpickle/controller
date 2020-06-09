package io.janstenpickle.switches.config

import cats.effect._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.circe.{CirceConfigSource, Events}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.model.{RemoteSwitchKey, State}
import natchez.Trace

object CirceSwitchStateConfigSource {

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    switchUpdatedPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, RemoteSwitchKey, State]] =
    CirceConfigSource
      .polling[F, G, RemoteSwitchKey, State](
        "switchState",
        pollingConfig,
        config,
        Events.fromDiff(
          switchUpdatedPublisher,
          (rk, s) => SwitchStateUpdateEvent(rk.toSwitchKey, s),
          (rk, _) => SwitchStateUpdateEvent(rk.toSwitchKey, State.Off)
        )
      )
}
