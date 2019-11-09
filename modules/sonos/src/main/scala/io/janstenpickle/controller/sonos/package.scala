package io.janstenpickle.controller

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.discovery.Discovery
import io.janstenpickle.controller.model.RemoteCommandSource

package object sonos {
  type SonosDiscovery[F[_]] = Discovery[F, NonEmptyString, SonosDevice[F]]
  val CommandSource = Some(RemoteCommandSource(NonEmptyString("sonos"), NonEmptyString("programatic")))
}
