package io.janstenpickle.controller.model.event

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{Command, Room}

sealed trait CommandEvent

object CommandEvent {
  case class MacroCommand(command: Command) extends CommandEvent
  case class ContextCommand(room: Room, name: NonEmptyString) extends CommandEvent
  case class ActivityCommand(room: Room, name: NonEmptyString) extends CommandEvent

  implicit val toOption: ToOption[CommandEvent] = ToOption.some
}
