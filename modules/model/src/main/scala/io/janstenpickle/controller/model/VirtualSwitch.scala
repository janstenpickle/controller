package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Codec
import io.circe.generic.semiauto._
import io.circe.refined._

case class VirtualSwitch(
  remote: NonEmptyString,
  commandSource: Option[RemoteCommandSource],
  device: NonEmptyString,
  command: NonEmptyString,
  room: Option[NonEmptyString]
)

object VirtualSwitch {
  implicit val eq: Eq[VirtualSwitch] = semi.eq

  implicit val virtualSwitchCodec: Codec.AsObject[VirtualSwitch] = deriveCodec
}
