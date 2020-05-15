package io.janstenpickle.controller.model.event

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.RemoteCommand

sealed trait RemoteEvent

object RemoteEvent {
  case class RemoteAddedEvent(remoteName: NonEmptyString, eventSource: String) extends RemoteEvent

  case class RemoteRemovedEvent(remoteName: NonEmptyString, eventSource: String) extends RemoteEvent

  case class RemoteSendCommandEvent(command: RemoteCommand) extends RemoteEvent

  case class RemoteLearnCommand(remoteName: NonEmptyString, remoteDevice: NonEmptyString, command: NonEmptyString)
      extends RemoteEvent

  implicit val toOption: ToOption[RemoteEvent] = ToOption.instance {
    case _: RemoteRemovedEvent => None
    case e: Any => Some(e)
  }
}
