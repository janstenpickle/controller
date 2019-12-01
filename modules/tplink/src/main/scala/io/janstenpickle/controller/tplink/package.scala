package io.janstenpickle.controller

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.discovery.Discovery
import io.janstenpickle.controller.model.RemoteCommandSource
import io.janstenpickle.controller.tplink.device.TplinkDevice

package object tplink {
  type TplinkDiscovery[F[_]] = Discovery[F, (NonEmptyString, DeviceType), TplinkDevice[F]]
  final val CommandSource = Some(RemoteCommandSource(NonEmptyString("tplink"), NonEmptyString("programatic")))
  final val DevName = "tplink"
}
