package io.janstenpickle.controller.model.event

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Codec
import io.circe.generic.extras.semiauto._
import io.circe.refined._
import io.janstenpickle.controller.model.{Command, DiscoveredDeviceKey, DiscoveredDeviceValue, Room}

sealed trait CommandEvent

object CommandEvent {
  case class MacroCommand(command: Command) extends CommandEvent
  case class ContextCommand(room: NonEmptyString, name: NonEmptyString) extends CommandEvent
  case class ActivityCommand(room: NonEmptyString, name: NonEmptyString) extends CommandEvent
  case class RemoteLearnCommand(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString)
      extends CommandEvent
  case class RenameDeviceCommand(key: DiscoveredDeviceKey, value: DiscoveredDeviceValue) extends CommandEvent

  implicit val toOption: ToOption[CommandEvent] = ToOption.some

  implicit val commandEventCodec: Codec.AsObject[CommandEvent] = deriveConfiguredCodec

}
