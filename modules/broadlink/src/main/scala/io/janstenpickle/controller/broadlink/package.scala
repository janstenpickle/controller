package io.janstenpickle.controller

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.broadlink.remote.RmRemote
import io.janstenpickle.controller.broadlink.switch.SpSwitch
import io.janstenpickle.controller.discovery.Discovery

package object broadlink {
  type BroadlinkDiscovery[F[_]] = Discovery[F, (NonEmptyString, String), Either[SpSwitch[F], RmRemote[F]]]

  final val DeviceName = "broadlink"

  def devType(dev: String): String = s"$DeviceName-$dev"
  def formatDeviceId(id: String): String = id.replace(":", "")
}
