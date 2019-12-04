package io.janstenpickle.controller.discovery

import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}

trait DeviceRename[F[_]] {
  def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Unit]
  def unassigned: F[Set[DiscoveredDeviceKey]]
  def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]]
}
