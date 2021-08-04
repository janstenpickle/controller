package io.janstenpickle.controller.event.remotecontrol

import cats.effect.kernel.Spawn
import cats.effect.{Async, Resource}
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.cache.Cache
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.event.config.EventDrivenConfigSource
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.Remote
import io.janstenpickle.controller.model.event.ConfigEvent
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import io.janstenpickle.trace4cats.model.AttributeValue

import scala.concurrent.duration._

object EventDrivenRemoteConfigSource {
  def apply[F[_]: Async, G[_]: Spawn](
    subscriber: EventSubscriber[F, ConfigEvent],
    source: String,
    k: ResourceKleisli[G, (SpanName, Map[String, String]), Span[G]],
    cacheTimeout: FiniteDuration = 20.minutes
  )(implicit trace: Trace[F], provide: Provide[G, F, Span[G]]): Resource[F, ConfigSource[F, NonEmptyString, Remote]] = {

    def span[A](name: String, remoteName: NonEmptyString, extraFields: (String, AttributeValue)*)(fa: F[A]): F[A] =
      trace.span(name) {
        trace.putAll(extraFields ++ List[(String, AttributeValue)]("remote.name" -> remoteName.value): _*) *> fa
      }

    EventDrivenConfigSource[F, G, ConfigEvent, NonEmptyString, Remote](subscriber, "remote", source, cacheTimeout, k) {
      case ConfigEvent.RemoteAddedEvent(remote, _) =>
        (state: Cache[F, NonEmptyString, Remote]) =>
          span("remote.config.added", remote.name)(state.set(remote.name, remote))
      case ConfigEvent.RemoteRemovedEvent(name, _) =>
        (state: Cache[F, NonEmptyString, Remote]) =>
          span("remote.config.removed", name)(state.remove(name))
    }
  }
}
