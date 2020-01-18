package io.janstenpickle.controller.model.event

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Command

sealed trait MacroEvent

object MacroEvent {
  case class StoreMacroEvent(name: NonEmptyString, commands: NonEmptyList[Command]) extends MacroEvent
  case class ExecuteMacro(name: NonEmptyString) extends MacroEvent
  case class ExecuteCommand(command: Command) extends MacroEvent
}
