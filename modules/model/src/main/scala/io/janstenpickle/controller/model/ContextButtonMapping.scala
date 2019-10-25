package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import eu.timepit.refined.types.string.NonEmptyString

sealed trait ContextButtonMapping {
  def name: NonEmptyString
}

object ContextButtonMapping {
  implicit val eq: Eq[ContextButtonMapping] = semi.eq

  case class Remote(
    name: NonEmptyString,
    remote: NonEmptyString,
    commandSource: Option[RemoteCommandSource],
    device: NonEmptyString,
    command: NonEmptyString
  ) extends ContextButtonMapping
  case class ToggleSwitch(name: NonEmptyString, device: NonEmptyString, switch: NonEmptyString)
      extends ContextButtonMapping
  case class Macro(name: NonEmptyString, `macro`: NonEmptyString) extends ContextButtonMapping
}
