package io.janstenpickle.deconz.config

import cats.Eq
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.{Codec, Decoder, Encoder}
import io.janstenpickle.deconz.model.ButtonAction
import shapeless.syntax.typeable._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

case class ActionMapping(button: ButtonAction, controller: ControllerAction)

object ActionMapping {
  implicit val circeConfig: Configuration = io.janstenpickle.controller.model.circeConfig

  implicit val durationDecoder: Decoder[FiniteDuration] = Decoder.decodeString
    .emapTry { dur =>
      Try(Duration(dur))
    }
    .emap(_.cast[FiniteDuration].toRight("Invalid duration format"))

  implicit val durationEncoder: Encoder[FiniteDuration] = Encoder.encodeString.contramap(_.toString())

  implicit val buttonActionCodec: Codec[ButtonAction] = deriveConfiguredCodec

  implicit val actionMappingEq: Eq[ActionMapping] = cats.derived.semi.eq
  implicit val actionMappingCodec: Codec[ActionMapping] = io.circe.generic.semiauto.deriveCodec
}
