package io.janstenpickle.controller.components.events

import cats.Parallel
import cats.effect.{BracketThrow, Concurrent, Resource, Timer}
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.event.activity.EventDrivenActivityConfigSource
import io.janstenpickle.controller.event.remotecontrol.{EventDrivenRemoteConfigSource, EventDrivenRemoteControls}
import io.janstenpickle.controller.event.switch.EventDrivenSwitchProvider
import io.janstenpickle.controller.events.Events
import io.janstenpickle.controller.remotecontrol.RemoteControlErrors
import io.janstenpickle.controller.switch.SwitchErrors

import scala.concurrent.duration.FiniteDuration
import cats.syntax.parallel._
import io.janstenpickle.controller.events.discovery.EventDrivenDeviceRename
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

object EventDrivenComponents {
  def apply[F[_]: Concurrent: Timer: Parallel: RemoteControlErrors: SwitchErrors: Trace, G[_]: BracketThrow](
    events: Events[F],
    commandTimeout: FiniteDuration,
    learnTimeout: FiniteDuration,
    k: ResourceKleisli[G, (SpanName, Map[String, String]), Span[G]],
  )(
    implicit
    provide: Provide[G, F, Span[G]]
  ): Resource[F, Components[F]] =
    (
      EventDrivenRemoteControls(
        events.remote.subscriberStream,
        events.command.publisher,
        events.source,
        commandTimeout,
        learnTimeout,
        k
      ),
      EventDrivenSwitchProvider(
        events.switch.subscriberStream,
        events.command.publisher,
        events.source,
        commandTimeout,
        k
      ),
      EventDrivenDeviceRename(
        events.discovery.subscriberStream,
        events.command.publisher,
        events.source,
        commandTimeout,
        k
      ),
      EventDrivenActivityConfigSource(events.config.subscriberStream, events.source, k),
      EventDrivenRemoteConfigSource(events.config.subscriberStream, events.source, k),
    ).parMapN {
      case (remote, switch, device, activityConfig, remoteConfig) =>
        Components
          .componentsMonoid[F]
          .empty
          .copy(
            remotes = remote,
            switches = switch,
            rename = device,
            activityConfig = activityConfig,
            remoteConfig = remoteConfig
          )
    }
}
