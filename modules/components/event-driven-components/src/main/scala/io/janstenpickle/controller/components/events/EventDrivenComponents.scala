package io.janstenpickle.controller.components.events

import cats.Parallel
import cats.effect.{Concurrent, Resource, Timer}
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.event.activity.EventDrivenActivityConfigSource
import io.janstenpickle.controller.event.remotecontrol.{EventDrivenRemoteConfigSource, EventDrivenRemoteControls}
import io.janstenpickle.controller.event.switch.EventDrivenSwitchProvider
import io.janstenpickle.controller.events.Events
import io.janstenpickle.controller.remotecontrol.RemoteControlErrors
import io.janstenpickle.controller.switch.SwitchErrors

import scala.concurrent.duration.FiniteDuration
import cats.syntax.parallel._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.discovery.EventDrivenDeviceRename
import io.janstenpickle.trace4cats.inject.Trace

object EventDrivenComponents {
  def apply[F[_]: Concurrent: Timer: Parallel: RemoteControlErrors: SwitchErrors: Trace, G[_]](
    events: Events[F],
    commandTimeout: FiniteDuration,
    learnTimeout: FiniteDuration
  )(
    implicit
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, Components[F]] =
    (
      EventDrivenRemoteControls(
        events.remote.subscriberStream,
        events.command.publisher,
        events.source,
        commandTimeout,
        learnTimeout
      ),
      EventDrivenSwitchProvider(
        events.switch.subscriberStream,
        events.command.publisher,
        events.source,
        commandTimeout
      ),
      EventDrivenDeviceRename(
        events.discovery.subscriberStream,
        events.command.publisher,
        events.source,
        commandTimeout
      ),
      EventDrivenActivityConfigSource(events.config.subscriberStream, events.source),
      EventDrivenRemoteConfigSource(events.config.subscriberStream, events.source),
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
