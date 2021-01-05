package io.janstenpickle.controller.sonos

import eu.timepit.refined.types.string.NonEmptyString

object Commands {
  final val PlayPause: NonEmptyString = NonEmptyString("play_pause")
  final val Previous: NonEmptyString = NonEmptyString("previous")
  final val Next: NonEmptyString = NonEmptyString("next")
  final val VolUp: NonEmptyString = NonEmptyString("vol_up")
  final val VolDown: NonEmptyString = NonEmptyString("vol_down")
  final val Mute: NonEmptyString = NonEmptyString("mute")
  final val Group: NonEmptyString = NonEmptyString("group")
}
