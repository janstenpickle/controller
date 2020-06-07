package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Codec
import io.circe.generic.semiauto._
import io.circe.refined._

case class SwitchKey(device: NonEmptyString, name: NonEmptyString)

object SwitchKey {
  implicit val eq: Eq[SwitchKey] = semi.eq

  implicit val switchKeyCodec: Codec.AsObject[SwitchKey] = deriveCodec
}
