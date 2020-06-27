package io.janstenpickle.controller.deconz.config

import cats.Eq
import cats.instances.string._
import cats.syntax.functor._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._
import io.circe.refined._
import io.janstenpickle.controller.model.Command

sealed trait ControllerAction

object ControllerAction {
  case class Context(room: NonEmptyString, name: NonEmptyString) extends ControllerAction
  case class Macro(command: Command) extends ControllerAction

  implicit val contextAction: Eq[ControllerAction] = cats.derived.semi.eq

  implicit val controllerActionDecoder: Decoder[ControllerAction] =
    deriveDecoder[Context].widen[ControllerAction].or(deriveDecoder[Macro].widen[ControllerAction])

  implicit val controllerActionEncoder: Encoder[ControllerAction] = Encoder {
    case c @ Context(_, _) => deriveEncoder[Context].apply(c)
    case m @ Macro(_) => deriveEncoder[Macro].apply(m)
  }
}
