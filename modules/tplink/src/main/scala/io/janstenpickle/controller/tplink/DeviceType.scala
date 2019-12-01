package io.janstenpickle.controller.tplink

sealed trait DeviceType

object DeviceType {
  case object SmartPlug extends DeviceType
  case object SmartStrip extends DeviceType
  case object SmartBulb extends DeviceType
}
