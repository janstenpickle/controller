package io.janstenpickle.controller

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.discovery.Discovery

package object kodi {
  type KodiDiscovery[F[_]] = Discovery[F, NonEmptyString, KodiDevice[F]]

  final val DeviceName = "kodi"
}
