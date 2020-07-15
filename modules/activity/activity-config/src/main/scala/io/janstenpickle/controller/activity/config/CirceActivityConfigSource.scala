package io.janstenpickle.controller.activity.config

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.string._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.circe.{eventSource, CirceConfigSource, Events}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Activity
import io.janstenpickle.controller.model.event.ConfigEvent
import io.janstenpickle.controller.model.event.ConfigEvent.{ActivityAddedEvent, ActivityRemovedEvent}
import io.janstenpickle.trace4cats.inject.Trace

object CirceActivityConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    configEventPublisher: EventPublisher[F, ConfigEvent]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, WritableConfigSource[F, String, Activity]] =
    CirceConfigSource
      .polling[F, G, String, Activity](
        "activities",
        pollingConfig,
        config,
        Events.fromDiffValues(
          configEventPublisher,
          ActivityAddedEvent(_, eventSource),
          a => ActivityRemovedEvent(a.room, a.name, eventSource)
        )
      )
}
