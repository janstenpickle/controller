package io.janstenpickle.controller.kodi

import cats.Applicative
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.instances.list._
import cats.syntax.flatMap._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.events.components.ComponentsStateToEvents
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.Remote
import io.janstenpickle.controller.model.event.{ConfigEvent, RemoteEvent, SwitchEvent}
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.switch.SwitchProvider

import scala.concurrent.duration._

object RefreshListener {
  def apply[F[_]: Concurrent: Timer](
    configPublisher: EventPublisher[F, ConfigEvent],
    switchPublisher: EventPublisher[F, SwitchEvent],
    switchEvents: EventSubscriber[F, SwitchEvent],
    remoteEvents: EventSubscriber[F, RemoteEvent],
    switches: SwitchProvider[F],
    remoteControls: RemoteControls[F],
    remoteConfig: ConfigSource[F, NonEmptyString, Remote]
  ): Resource[F, F[Unit]] = {
    val listen = switchEvents.subscribe
      .either(remoteEvents.subscribe)
      .evalMap {
        case Left(SwitchEvent.SwitchStateUpdateEvent(key, _, None)) =>
          switches.getSwitches.flatMap { sws =>
            if (sws.contains(key))
              Stream
                .evals(ComponentsStateToEvents.remotes[F](remoteConfig, "kodi"))
                .through(configPublisher.pipe)
                .compile
                .drain
            else Applicative[F].unit
          }
        case Right(RemoteEvent.RemoteSentCommandEvent(command))
            if command.name == Commands.PlayPause || command.name == Commands.Mute =>
          remoteControls
            .provides(command.remote)
            .ifM(
              remoteControls.listCommands.flatMap { cmds =>
                if (cmds.contains(command))
                  Stream.evals(ComponentsStateToEvents.switches(switches)).through(switchPublisher.pipe).compile.drain
                else Applicative[F].unit
              },
              Applicative[F].unit
            )
        case _ => Applicative[F].unit
      }

    Stream.retry(listen.compile.drain, 5.seconds, _ + 1.second, Int.MaxValue).compile.drain.background
  }
}
