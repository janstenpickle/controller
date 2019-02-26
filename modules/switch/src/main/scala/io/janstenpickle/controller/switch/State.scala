package io.janstenpickle.controller.switch

sealed trait State {
  val value: String
  override def toString: String = value
}

object State {
  case object On extends State {
    override val value: String = "on"
  }
  case object Off extends State {
    override val value: String = "off"
  }
}
