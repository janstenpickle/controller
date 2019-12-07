package io.janstenpickle.controller.tplink

import eu.timepit.refined.types.string.NonEmptyString

sealed trait DeviceType {
  def model: NonEmptyString
}

object DeviceType {
  case class SmartPlug(model: NonEmptyString) extends DeviceType
  case class SmartStrip(model: NonEmptyString) extends DeviceType
  case class SmartBulb(model: NonEmptyString) extends DeviceType
}
