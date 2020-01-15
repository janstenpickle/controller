package io.janstenpickle.deconz.config

import cats.Eq
import cats.instances.string._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Command

sealed trait ControllerAction

object ControllerAction {
  case class Context(room: NonEmptyString, name: NonEmptyString) extends ControllerAction
  case class Macro(command: Command) extends ControllerAction

  implicit val contextAction: Eq[ControllerAction] = cats.derived.semi.eq
}
