package io.janstenpickle.controller.model

import cats.Eq
import cats.instances.boolean._

sealed trait State {
  val value: String
  val isOn: Boolean
  override def toString: String = value
}

object State {
  implicit val eq: Eq[State] = Eq.by(_.isOn)

  case object On extends State {
    override val value: String = "on"
    override val isOn: Boolean = true
  }
  case object Off extends State {
    override val value: String = "off"
    override val isOn: Boolean = false
  }
}
