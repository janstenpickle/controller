package io.janstenpickle.controller

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.RemoteCommandSource

package object sonos {
  val CommandSource = Some(RemoteCommandSource(NonEmptyString("sonos"), NonEmptyString("programatic")))
}
