package io.janstenpickle.controller.model

import cats.Eq
import cats.instances.string._
import cats.derived.semi

case class DiscoveredDeviceKey(deviceId: String, deviceType: String)

object DiscoveredDeviceKey {
  implicit val discoveredDeviceKeyEq: Eq[DiscoveredDeviceKey] = semi.eq
}
