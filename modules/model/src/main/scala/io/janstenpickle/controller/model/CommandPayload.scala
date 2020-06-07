package io.janstenpickle.controller.model

import cats.Eq
import cats.instances.string._
import io.circe.{Codec, Decoder, Encoder}

case class CommandPayload(hexValue: String) extends AnyVal

object CommandPayload {
  implicit val commandPayloadEq: Eq[CommandPayload] = Eq.by(_.hexValue)
  implicit val commandPayloadCodec: Codec[CommandPayload] =
    Codec.from(Decoder.decodeString.map(CommandPayload(_)), Encoder.encodeString.contramap(_.hexValue))
}
