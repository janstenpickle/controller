package io.janstenpickle.controller.model.event

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Codec
import io.circe.generic.extras.semiauto._
import io.circe.refined._
import io.janstenpickle.controller.model.Command

sealed trait MacroEvent

object MacroEvent {
  case class StoredMacroEvent(name: NonEmptyString, commands: NonEmptyList[Command]) extends MacroEvent
  case class ExecutedMacro(name: NonEmptyString) extends MacroEvent
  case class ExecutedCommand(command: Command) extends MacroEvent

  implicit val toOption: ToOption[MacroEvent] = ToOption.some

  implicit val macroEventCodec: Codec[MacroEvent] = deriveConfiguredCodec
}
