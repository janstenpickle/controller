package io.janstenpickle.controller.switch

sealed trait State {
  val value: String
  val isOn: Boolean
  override def toString: String = value
}

object State {
  case object On extends State {
    override val value: String = "on"
    override val isOn: Boolean = true
  }
  case object Off extends State {
    override val value: String = "off"
    override val isOn: Boolean = false
  }
}
