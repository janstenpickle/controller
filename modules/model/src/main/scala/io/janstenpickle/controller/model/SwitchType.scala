package io.janstenpickle.controller.model

import cats.Eq
import cats.instances.string._

sealed trait SwitchType

object SwitchType {
  case object Switch extends SwitchType
  case object Plug extends SwitchType
  case object Bulb extends SwitchType
  case object Virtual extends SwitchType
  case object Multi extends SwitchType

  implicit val eq: Eq[SwitchType] = Eq.by(_.toString.toLowerCase)
}
