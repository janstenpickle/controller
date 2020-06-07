package io.janstenpickle.controller.model

import cats.Eq
import cats.data.NonEmptyList
import cats.derived.semi
import cats.instances.list._
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.extras.semiauto._
import io.circe.refined._

sealed trait SwitchAction {
  val value: String
  override def toString: String = value
}
object SwitchAction {
  case object Nothing extends SwitchAction {
    override final val value: String = "nothing"
  }
  case object Perform extends SwitchAction {
    override final val value: String = "perform"
  }
  case object Opposite extends SwitchAction {
    override final val value: String = "opposite"
  }

  implicit val eq: Eq[SwitchAction] = semi.eq

  implicit val switchActionCodec: Codec[SwitchAction] =
    Codec.from(Decoder.decodeString.emap(fromString), Encoder.encodeString.contramap(_.value))

  def fromString(action: String): Either[String, SwitchAction] = action.toLowerCase match {
    case Nothing.value => Right(Nothing)
    case Perform.value => Right(Perform)
    case Opposite.value => Right(Opposite)
    case _ => Left(s"Invalid value '$action' for action")
  }
}

case class SwitchRef(
  name: NonEmptyString,
  device: NonEmptyString,
  onAction: SwitchAction = SwitchAction.Perform,
  offAction: SwitchAction = SwitchAction.Perform
)

object SwitchRef {
  implicit val eq: Eq[SwitchRef] = semi.eq
  implicit val switchRefCodec: Codec.AsObject[SwitchRef] = deriveConfiguredCodec
}

case class MultiSwitch(
  name: NonEmptyString,
  primary: SwitchRef,
  secondaries: NonEmptyList[SwitchRef],
  room: Option[NonEmptyString] = None
)

object MultiSwitch {
  implicit val eq: Eq[MultiSwitch] = semi.eq
  implicit val multiSwitch: Codec.AsObject[MultiSwitch] = deriveConfiguredCodec
}
