package io.janstenpickle.controller.model

import cats.Eq
import cats.instances.string._
import io.circe.{Codec, Decoder, Encoder}

sealed trait SwitchType

object SwitchType {
  case object Switch extends SwitchType
  case object Plug extends SwitchType
  case object Bulb extends SwitchType
  case object Virtual extends SwitchType
  case object Multi extends SwitchType

  implicit val eq: Eq[SwitchType] = Eq.by(_.toString.toLowerCase)

  implicit val switchTypeDecoder: Decoder[SwitchType] = Decoder.decodeString.emap {
    case "switch" => Right(Switch)
    case "plug" => Right(Plug)
    case "bulb" => Right(Bulb)
    case "virtual" => Right(Virtual)
    case "multi" => Right(Multi)
    case v => Left(s"Invalid switch type '$v'")
  }

  implicit val switchTypeEncoder: Encoder[SwitchType] = Encoder.encodeString.contramap(_.toString.toLowerCase)

  implicit val switchTypeCodec: Codec[SwitchType] = Codec.from(switchTypeDecoder, switchTypeEncoder)
}
