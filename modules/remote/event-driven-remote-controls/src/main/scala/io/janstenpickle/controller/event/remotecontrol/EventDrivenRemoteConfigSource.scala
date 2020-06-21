package io.janstenpickle.controller.event.remotecontrol

import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.functor._
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.cache.Cache
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.event.config.EventDrivenConfigSource
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.Remote
import io.janstenpickle.controller.model.event.ConfigEvent
import natchez.{Trace, TraceValue}

import scala.concurrent.duration._

object EventDrivenRemoteConfigSource {
  def apply[F[_]: Concurrent: Timer, G[_]](
    subscriber: EventSubscriber[F, ConfigEvent],
    source: String,
    cacheTimeout: FiniteDuration = 20.minutes
  )(
    implicit trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, ConfigSource[F, NonEmptyString, Remote]] = {

    def span[A](name: String, remoteName: NonEmptyString, extraFields: (String, TraceValue)*)(fa: F[A]): F[A] =
      trace.span(name) {
        trace.put(extraFields ++ List[(String, TraceValue)]("remote.name" -> remoteName.value): _*) *> fa
      }

    EventDrivenConfigSource[F, G, ConfigEvent, NonEmptyString, Remote](subscriber, "remote", source, cacheTimeout) {
      case ConfigEvent.RemoteAddedEvent(remote, _) =>
        (state: Cache[F, NonEmptyString, Remote]) =>
          span("remote.config.added", remote.name)(state.set(remote.name, remote))
      case ConfigEvent.RemoteRemovedEvent(name, _) =>
        (state: Cache[F, NonEmptyString, Remote]) =>
          span("remote.config.removed", name)(state.remove(name))
    }
  }
}
