package io.janstenpickle.controller.remote.config

import cats.effect._
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import io.circe.refined._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.circe.{eventSource, CirceConfigSource, Events}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Remote
import io.janstenpickle.controller.model.event.ConfigEvent
import io.janstenpickle.controller.model.event.ConfigEvent.{RemoteAddedEvent, RemoteRemovedEvent}
import natchez.Trace

object CirceRemoteConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    configEventPublisher: EventPublisher[F, ConfigEvent]
  )(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, NonEmptyString, Remote]] =
    CirceConfigSource
      .polling[F, G, NonEmptyString, Remote](
        "remotes",
        pollingConfig,
        config,
        Events.fromDiffValues(
          configEventPublisher,
          RemoteAddedEvent(_, eventSource),
          r => RemoteRemovedEvent(r.name, eventSource)
        )
      )

}
