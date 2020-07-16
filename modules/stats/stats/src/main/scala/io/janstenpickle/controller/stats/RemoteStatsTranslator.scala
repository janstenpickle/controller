package io.janstenpickle.controller.stats

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.event.RemoteEvent
import io.janstenpickle.controller.model.event.RemoteEvent._

object RemoteStatsTranslator {
  def apply[F[_]](remoteEventSubscriber: EventSubscriber[F, RemoteEvent]): fs2.Stream[F, Stats] =
    remoteEventSubscriber.subscribe
      .mapAccumulate(Set.empty[NonEmptyString]) {
        case (state, RemoteAddedEvent(remoteName, _, _)) =>
          val newState = state + remoteName
          (newState, Stats.RemoteDevices(newState.size))
        case (state, RemoteRemovedEvent(remoteName, _)) =>
          val newState = state - remoteName
          (newState, Stats.RemoteDevices(newState.size))
        case (state, RemoteSentCommandEvent(command)) =>
          (state, Stats.SendRemoteCommand(command.remote, command.source, command.device, command.name))
        case (state, RemoteLearntCommand(remoteName, remoteDevice, _, command)) =>
          (state, Stats.LearnRemoteCommand(remoteName, remoteDevice, command))
      }
      .map(_._2)
}
