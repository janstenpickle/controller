package io.janstenpickle.controller.discovery

import cats.kernel.Monoid
import io.janstenpickle.controller.model.DiscoveredDeviceKey

case class Discovered[K, V](unmapped: Map[DiscoveredDeviceKey, Map[String, String]], devices: Map[K, V])

object Discovered {
  implicit def discoveredMonoid[K, V]: Monoid[Discovered[K, V]] = new Monoid[Discovered[K, V]] {
    override def empty: Discovered[K, V] = Discovered(Map.empty, Map.empty)
    override def combine(x: Discovered[K, V], y: Discovered[K, V]): Discovered[K, V] =
      Discovered(x.unmapped ++ y.unmapped, x.devices ++ y.devices)
  }
}
