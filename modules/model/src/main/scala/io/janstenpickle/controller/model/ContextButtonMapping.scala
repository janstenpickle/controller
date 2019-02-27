package io.janstenpickle.controller.model

import eu.timepit.refined.types.string.NonEmptyString

sealed trait ContextButtonMapping {
  def name: NonEmptyString
}

object ContextButtonMapping {
  case class Remote(name: NonEmptyString, remote: NonEmptyString, device: NonEmptyString, command: NonEmptyString)
      extends ContextButtonMapping
  case class Macro(name: NonEmptyString, `macro`: NonEmptyString) extends ContextButtonMapping
}
