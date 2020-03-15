package io.janstenpickle.controller.model.event

import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}

sealed trait DeviceDiscoveryEvent {
  def key: DiscoveredDeviceKey
}

object DeviceDiscoveryEvent {
  case class DeviceDiscovered(key: DiscoveredDeviceKey, value: DiscoveredDeviceValue) extends DeviceDiscoveryEvent

  case class UnmappedDiscovered(key: DiscoveredDeviceKey) extends DeviceDiscoveryEvent

  case class DeviceRename(key: DiscoveredDeviceKey, value: DiscoveredDeviceValue) extends DeviceDiscoveryEvent

  case class DeviceRemoved(key: DiscoveredDeviceKey) extends DeviceDiscoveryEvent

  implicit val toOption: ToOption[DeviceDiscoveryEvent] = ToOption.instance {
    case _: DeviceRemoved => None
    case e: Any => Some(e)
  }
}
