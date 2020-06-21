package io.janstenpickle.controller.event.activity

import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.cache.Cache
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.event.config.EventDrivenConfigSource
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.Activity
import io.janstenpickle.controller.model.event.ConfigEvent
import natchez.{Trace, TraceValue}

object EventDrivenActivityConfigSource {
  def apply[F[_]: Concurrent: Timer, G[_]](subscriber: EventSubscriber[F, ConfigEvent], source: String)(
    implicit trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, ConfigSource[F, String, Activity]] = {
    def span[A](name: String, activityName: NonEmptyString, room: NonEmptyString, extraFields: (String, TraceValue)*)(
      a: A
    ): F[A] =
      trace.span(name) {
        trace
          .put(
            extraFields ++ List[(String, TraceValue)]("activity.name" -> activityName.value, "room" -> room.value): _*
          )
          .as(a)
      }

    EventDrivenConfigSource[F, G, ConfigEvent, String, Activity](subscriber, "activity", source) {
      case ConfigEvent.ActivityAddedEvent(activity, _) =>
        (state: Cache[F, String, Activity]) =>
          span("activity.config.added", activity.name, activity.room)(
            state.set(s"${activity.room}-${activity.name}", activity)
          )
      case ConfigEvent.ActivityRemovedEvent(room, name, _) =>
        (state: Cache[F, String, Activity]) =>
          span("activity.config.removed", name, room)(state.remove(s"$room-$name"))
    }
  }
}
