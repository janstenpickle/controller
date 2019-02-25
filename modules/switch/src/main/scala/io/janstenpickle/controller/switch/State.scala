package io.janstenpickle.controller.switch

sealed trait State {
  val value: String
  override def toString: String = value
}

object State {
  case object On extends State {
    override val value: String = "ON"
  }
  case object Off extends State {
    override val value: String = "OFF"
  }

  val parse: Int => State = {
    case 1 => On
    case _ => Off
  }
}
