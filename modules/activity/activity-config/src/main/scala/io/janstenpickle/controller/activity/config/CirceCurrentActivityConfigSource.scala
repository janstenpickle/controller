package io.janstenpickle.controller.activity.config

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.string._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.refined._
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.circe.{CirceConfigSource, Events}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.model.event.ActivityUpdateEvent
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

object CirceCurrentActivityConfigSource {
  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    activityUpdateEventPublisher: EventPublisher[F, ActivityUpdateEvent],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[F, WritableConfigSource[F, Room, NonEmptyString]] =
    CirceConfigSource
      .polling[F, G, NonEmptyString, NonEmptyString](
        "currentActivity",
        pollingConfig,
        config,
        Events.fromDiff(activityUpdateEventPublisher, ActivityUpdateEvent(_, _), ActivityUpdateEvent(_, _)),
        k
      )
}
