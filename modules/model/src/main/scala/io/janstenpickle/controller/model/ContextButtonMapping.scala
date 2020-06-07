package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Codec
import io.circe.generic.extras.semiauto._
import io.circe.refined._

sealed trait ContextButtonMapping {
  def name: NonEmptyString
}

object ContextButtonMapping {
  implicit val eq: Eq[ContextButtonMapping] = semi.eq
  implicit val contextButtonMapping: Codec.AsObject[ContextButtonMapping] = deriveConfiguredCodec

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
