package io.janstenpickle.controller

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.discovery.Discovery
import io.janstenpickle.controller.tplink.TplinkDiscovery.TplinkInstance
import io.janstenpickle.controller.tplink.hs100.HS100SmartPlug

package object tplink {
  type TplinkDiscovery[F[_]] = Discovery[F, (NonEmptyString, DeviceType), HS100SmartPlug[F]]
}
