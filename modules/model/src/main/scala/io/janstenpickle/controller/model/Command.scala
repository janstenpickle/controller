package io.janstenpickle.controller.model

import eu.timepit.refined.types.string.NonEmptyString

sealed trait Command

object Command {
  case class Sleep(millis: Long) extends Command
  case class ToggleSwitch(device: NonEmptyString, name: NonEmptyString) extends Command
  case class SwitchOn(device: NonEmptyString, name: NonEmptyString) extends Command
  case class SwitchOff(device: NonEmptyString, name: NonEmptyString) extends Command
  case class Remote(remote: NonEmptyString, device: NonEmptyString, command: NonEmptyString) extends Command
  case class Macro(name: NonEmptyString) extends Command
}
