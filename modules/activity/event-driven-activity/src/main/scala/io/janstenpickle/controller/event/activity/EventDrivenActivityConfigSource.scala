package io.janstenpickle.controller.event.activity

import cats.effect.Resource
import cats.effect.kernel.{Async, Spawn}
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.cache.Cache
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.event.config.EventDrivenConfigSource
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.Activity
import io.janstenpickle.controller.model.event.ConfigEvent
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import io.janstenpickle.trace4cats.model.AttributeValue

import scala.concurrent.duration._

object EventDrivenActivityConfigSource {
  def apply[F[_]: Async, G[_]: Spawn](
    subscriber: EventSubscriber[F, ConfigEvent],
    source: String,
    k: ResourceKleisli[G, (SpanName, Map[String, String]), Span[G]],
    cacheTimeout: FiniteDuration = 20.minutes,
  )(implicit trace: Trace[F], provide: Provide[G, F, Span[G]]): Resource[F, ConfigSource[F, String, Activity]] = {
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

    EventDrivenConfigSource[F, G, ConfigEvent, String, Activity](subscriber, "activity", source, cacheTimeout, k) {
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
