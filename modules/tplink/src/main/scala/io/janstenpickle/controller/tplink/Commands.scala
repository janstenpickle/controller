package io.janstenpickle.controller.tplink

import eu.timepit.refined.types.string.NonEmptyString

object Commands {
  final val BrightnessUp = NonEmptyString("brightness_up")
  final val BrightnessDown = NonEmptyString("brightness_up")
}
