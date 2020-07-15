package io.janstenpickle.controller.event.activity

import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.cache.Cache
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.event.config.EventDrivenConfigSource
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.Activity
import io.janstenpickle.controller.model.event.ConfigEvent
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.AttributeValue

import scala.concurrent.duration._

object EventDrivenActivityConfigSource {
  def apply[F[_]: Concurrent: Timer, G[_]](
    subscriber: EventSubscriber[F, ConfigEvent],
    source: String,
    cacheTimeout: FiniteDuration = 20.minutes
  )(
    implicit trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, ConfigSource[F, String, Activity]] = {
    def span[A](
      name: String,
      activityName: NonEmptyString,
      room: NonEmptyString,
      extraFields: (String, AttributeValue)*
    )(fa: F[A]): F[A] =
      trace.span(name) {
        trace
          .putAll(
            extraFields ++ List[(String, AttributeValue)]("activity.name" -> activityName.value, "room" -> room.value): _*
          ) *> fa
      }

    EventDrivenConfigSource[F, G, ConfigEvent, String, Activity](subscriber, "activity", source, cacheTimeout) {
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
