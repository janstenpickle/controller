package io.janstenpickle.controller.stats

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.model.Command.{Macro, Remote, Sleep, SwitchOff, SwitchOn, ToggleSwitch}

package object `macro` {
  val commandType: Command => NonEmptyString = {
    case _: Sleep => NonEmptyString("sleep")
    case _: ToggleSwitch => NonEmptyString("toggle-switch")
    case _: SwitchOn => NonEmptyString("switch-on")
    case _: SwitchOff => NonEmptyString("switch-off")
    case _: Remote => NonEmptyString("remote")
    case _: Macro => NonEmptyString("macro")
  }
}
