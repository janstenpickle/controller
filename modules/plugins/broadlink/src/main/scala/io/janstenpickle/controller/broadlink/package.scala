package io.janstenpickle.controller

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.discovery.Discovery

package object broadlink {
  type BroadlinkDiscovery[F[_]] = Discovery[F, (NonEmptyString, String), BroadlinkDevice[F]]

  final val DeviceName = "broadlink"

  def devType(dev: String): String = s"$DeviceName-$dev"
  def formatDeviceId(id: String): String = id.replace(":", "")
}
