package io.janstenpickle.controller.event.activity

import cats.effect.{Concurrent, Resource, Timer}
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.event.config.EventDrivenConfigSource
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.Activity
import io.janstenpickle.controller.model.event.ConfigEvent

object EventDrivenActivityConfigSource {
  def apply[F[_]: Concurrent: Timer](
    subscriber: EventSubscriber[F, ConfigEvent]
  ): Resource[F, ConfigSource[F, String, Activity]] =
    EventDrivenConfigSource[F, ConfigEvent, String, Activity](subscriber) {
      case ConfigEvent.ActivityAddedEvent(activity, _) =>
        (state: Map[String, Activity]) =>
          state.updated(s"${activity.room}-${activity.name}", activity)
      case ConfigEvent.ActivityRemovedEvent(room, name, _) =>
        (state: Map[String, Activity]) =>
          state - s"$room-$name"
    }
}
