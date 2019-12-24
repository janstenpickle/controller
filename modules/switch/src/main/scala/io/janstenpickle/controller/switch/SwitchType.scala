package io.janstenpickle.controller.switch

sealed trait SwitchType

object SwitchType {
  case object Switch extends SwitchType
  case object Plug extends SwitchType
  case object Bulb extends SwitchType
  case object Virtual extends SwitchType
  case object Multi extends SwitchType
}
