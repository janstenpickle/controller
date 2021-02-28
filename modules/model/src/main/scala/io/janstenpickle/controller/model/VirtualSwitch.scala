package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semiauto
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.semiauto._
import io.circe.refined._
import io.circe.syntax._

sealed trait VirtualSwitch {
  def remote: NonEmptyString
  def commandSource: Option[RemoteCommandSource]
  def device: NonEmptyString
  def room: Option[NonEmptyString]
  def name: NonEmptyString
}

object VirtualSwitch {
  implicit val eq: Eq[VirtualSwitch] = semiauto.eq

  case class Toggle(
    remote: NonEmptyString,
    commandSource: Option[RemoteCommandSource],
    device: NonEmptyString,
    command: NonEmptyString,
    room: Option[NonEmptyString]
  ) extends VirtualSwitch {
    override def name: NonEmptyString = command
  }

  object Toggle {
    implicit val eq: Eq[Toggle] = semiauto.eq

    implicit val toggleCodec: Codec.AsObject[Toggle] = deriveCodec

  }

  case class OnOff(
    remote: NonEmptyString,
    commandSource: Option[RemoteCommandSource],
    device: NonEmptyString,
    name: NonEmptyString,
    on: NonEmptyString,
    off: NonEmptyString,
    room: Option[NonEmptyString]
  ) extends VirtualSwitch

  object OnOff {
    implicit val eq: Eq[OnOff] = semiauto.eq

    implicit val onOffCodec: Codec.AsObject[OnOff] = deriveCodec

  }

  implicit val virtualSwitchEncoder: Encoder[VirtualSwitch] = Encoder.instance {
    case v @ Toggle(_, _, _, _, _) => v.asJson
    case v @ OnOff(_, _, _, _, _, _, _) => v.asJson
  }

  implicit val virtualSwitchDecoder: Decoder[VirtualSwitch] = Decoder[Toggle].widen.or(Decoder[OnOff].widen)
}
