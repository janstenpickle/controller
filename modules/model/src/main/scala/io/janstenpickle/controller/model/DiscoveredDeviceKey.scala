package io.janstenpickle.controller.model

import cats.Eq
import cats.instances.string._
import cats.derived.semi
import io.circe.Codec
import io.circe.generic.semiauto._

case class DiscoveredDeviceKey(deviceId: String, deviceType: String)

object DiscoveredDeviceKey {
  implicit val discoveredDeviceKeyEq: Eq[DiscoveredDeviceKey] = semi.eq
  implicit val discoveredDeviceKeyCodec: Codec.AsObject[DiscoveredDeviceKey] = deriveCodec
}
