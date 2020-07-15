package io.janstenpickle.controller.configsource.circe

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.string._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Button
import io.janstenpickle.controller.model.event.ConfigEvent
import io.janstenpickle.controller.model.event.ConfigEvent.{ButtonAddedEvent, ButtonRemovedEvent}
import io.janstenpickle.trace4cats.inject.Trace

object CirceButtonConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    configEventPublisher: EventPublisher[F, ConfigEvent]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, WritableConfigSource[F, String, Button]] =
    CirceConfigSource
      .polling[F, G, String, Button](
        "buttons",
        pollingConfig,
        config,
        Events.fromDiffValues(
          configEventPublisher,
          ButtonAddedEvent(_, eventSource),
          b => ButtonRemovedEvent(b.name, b.room, eventSource)
        )
      )
}
