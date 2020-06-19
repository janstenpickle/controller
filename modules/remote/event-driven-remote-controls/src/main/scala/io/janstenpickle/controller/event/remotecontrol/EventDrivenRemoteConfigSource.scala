package io.janstenpickle.controller.event.remotecontrol

import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.event.config.EventDrivenConfigSource
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.Remote
import io.janstenpickle.controller.model.event.ConfigEvent
import natchez.{Trace, TraceValue}

object EventDrivenRemoteConfigSource {
  def apply[F[_]: Concurrent: Timer, G[_]](subscriber: EventSubscriber[F, ConfigEvent], source: String)(
    implicit trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, ConfigSource[F, NonEmptyString, Remote]] = {

    def span[A](name: String, remoteName: NonEmptyString, extraFields: (String, TraceValue)*)(a: A): F[A] =
      trace.span(name) {
        trace.put(extraFields ++ List[(String, TraceValue)]("remote.name" -> remoteName.value): _*).as(a)
      }

    EventDrivenConfigSource[F, G, ConfigEvent, NonEmptyString, Remote](subscriber, "remote", source) {
      case ConfigEvent.RemoteAddedEvent(remote, _) =>
        (state: Map[NonEmptyString, Remote]) =>
          span("remote.config.added", remote.name)(state.updated(remote.name, remote))
      case ConfigEvent.RemoteRemovedEvent(name, _) =>
        (state: Map[NonEmptyString, Remote]) =>
          span("remote.config.removed", name)(state.removed(name))
    }
  }
}
