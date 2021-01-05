package io.janstenpickle.controller.discovery

import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}

trait DiscoveredDevice[F[_]] {
  def key: DiscoveredDeviceKey
  def value: DiscoveredDeviceValue
  def refresh: F[Unit]
  def updatedKey: F[String]
}
