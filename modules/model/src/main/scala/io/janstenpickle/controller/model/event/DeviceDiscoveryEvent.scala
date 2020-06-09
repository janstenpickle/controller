package io.janstenpickle.controller.model.event

import io.circe.Codec
import io.circe.generic.extras.semiauto._
import io.circe.refined._
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}

sealed trait DeviceDiscoveryEvent {
  def key: DiscoveredDeviceKey
}

object DeviceDiscoveryEvent {
  case class DeviceDiscovered(key: DiscoveredDeviceKey, value: DiscoveredDeviceValue) extends DeviceDiscoveryEvent

  case class UnmappedDiscovered(key: DiscoveredDeviceKey, metadata: Map[String, String]) extends DeviceDiscoveryEvent

  case class DeviceRename(key: DiscoveredDeviceKey, value: DiscoveredDeviceValue) extends DeviceDiscoveryEvent

  case class DeviceRemoved(key: DiscoveredDeviceKey) extends DeviceDiscoveryEvent

  implicit val toOption: ToOption[DeviceDiscoveryEvent] = ToOption.instance {
    case _: DeviceRemoved => None
    case e: Any => Some(e)
  }

  implicit val deviceDiscoveryEventCodec: Codec[DeviceDiscoveryEvent] = deriveConfiguredCodec
}
