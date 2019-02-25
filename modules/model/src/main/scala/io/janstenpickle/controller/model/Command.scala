package io.janstenpickle.controller.model

import eu.timepit.refined.types.string.NonEmptyString

sealed trait Command

object Command {
  case class Sleep(millis: Long) extends Command
  case class ToggleSwitch(name: NonEmptyString) extends Command
  case class RemoteCommand(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString) extends Command
  case class MacroCommand(name: NonEmptyString) extends Command
}
