package io.janstenpickle.controller.model

import cats.Eq
import cats.instances.boolean._

sealed trait State {
  val value: String
  val intValue: Int
  val isOn: Boolean
  override def toString: String = value
}

object State {
  implicit val eq: Eq[State] = Eq.by(_.isOn)

  case object On extends State {
    override val value: String = "on"
    override val isOn: Boolean = true
    override val intValue: Int = 1
  }
  case object Off extends State {
    override val value: String = "off"
    override val isOn: Boolean = false
    override val intValue: Int = 0
  }

  def fromBoolean(boolean: Boolean): State = if (boolean) State.On else State.Off
}
