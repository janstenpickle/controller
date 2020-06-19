package io.janstenpickle.controller.model.event

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Codec
import io.circe.generic.extras.semiauto._
import io.circe.refined._
import io.janstenpickle.controller.model.{RemoteCommand, RemoteCommandSource}

sealed trait RemoteEvent

object RemoteEvent {
  case class RemoteAddedEvent(remoteName: NonEmptyString, eventSource: String) extends RemoteEvent

  case class RemoteRemovedEvent(remoteName: NonEmptyString, eventSource: String) extends RemoteEvent

  case class RemoteSentCommandEvent(command: RemoteCommand) extends RemoteEvent

  case class RemoteLearntCommand(
    remoteName: NonEmptyString,
    remoteDevice: NonEmptyString,
    commandSource: Option[RemoteCommandSource],
    command: NonEmptyString
  ) extends RemoteEvent

  // TODO add remote forgot command

  implicit val toOption: ToOption[RemoteEvent] = ToOption.instance {
    case _: RemoteRemovedEvent => None
    case e: Any => Some(e)
  }

  implicit val remoteEventCodec: Codec[RemoteEvent] = deriveConfiguredCodec

}
