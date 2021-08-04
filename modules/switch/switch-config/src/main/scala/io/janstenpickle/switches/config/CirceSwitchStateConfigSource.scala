package io.janstenpickle.switches.config

import cats.effect._
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.circe.{CirceConfigSource, Events}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.model.{RemoteSwitchKey, State}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

object CirceSwitchStateConfigSource {

  def apply[F[_]: Sync: Trace, G[_]: Async](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    switchUpdatedPublisher: EventPublisher[F, SwitchStateUpdateEvent],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[F, WritableConfigSource[F, RemoteSwitchKey, State]] =
    CirceConfigSource
      .polling[F, G, RemoteSwitchKey, State](
        "switchState",
        pollingConfig,
        config,
        Events.fromDiff(
          switchUpdatedPublisher,
          (rk, s) => SwitchStateUpdateEvent(rk.toSwitchKey, s),
          (rk, _) => SwitchStateUpdateEvent(rk.toSwitchKey, State.Off)
        ),
        k
      )
}
