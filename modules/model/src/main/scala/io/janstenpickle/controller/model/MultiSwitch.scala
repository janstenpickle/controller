package io.janstenpickle.controller.model

import cats.Eq
import cats.data.NonEmptyList
import cats.derived.semi
import cats.instances.list._
import cats.instances.string._
import cats.kernel.Monoid
import eu.timepit.refined.types.string.NonEmptyString

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
}

case class MultiSwitch(name: NonEmptyString, primary: SwitchRef, secondaries: NonEmptyList[SwitchRef])

object MultiSwitch {
  implicit val eq: Eq[MultiSwitch] = semi.eq
}
