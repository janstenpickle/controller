package io.janstenpickle.controller.configsource.circe

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString
import eu.timepit.refined.cats._
import io.circe.refined._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.model.event.ActivityUpdateEvent
import natchez.Trace

object CirceCurrentActivityConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    activityUpdateEventPublisher: EventPublisher[F, ActivityUpdateEvent]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, WritableConfigSource[F, Room, NonEmptyString]] =
    CirceConfigSource
      .polling[F, G, NonEmptyString, NonEmptyString](
        "currentActivity",
        pollingConfig,
        config,
        Events.fromDiff(activityUpdateEventPublisher, ActivityUpdateEvent(_, _), ActivityUpdateEvent(_, _))
      )
}
