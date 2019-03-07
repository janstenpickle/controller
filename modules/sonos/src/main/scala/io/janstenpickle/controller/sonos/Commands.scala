package io.janstenpickle.controller.sonos

import eu.timepit.refined.types.string.NonEmptyString

object Commands {
  val PlayPause: NonEmptyString = NonEmptyString("play_pause")
  val Previous: NonEmptyString = NonEmptyString("previous")
  val Next: NonEmptyString = NonEmptyString("next")
  val VolUp: NonEmptyString = NonEmptyString("vol_up")
  val VolDown: NonEmptyString = NonEmptyString("vol_down")
  val Mute: NonEmptyString = NonEmptyString("mute")
  val Group: NonEmptyString = NonEmptyString("group")
}
