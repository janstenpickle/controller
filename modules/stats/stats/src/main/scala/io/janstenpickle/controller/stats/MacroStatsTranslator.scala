package io.janstenpickle.controller.stats

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.model.Command.{Macro, Remote, Sleep, SwitchOff, SwitchOn, ToggleSwitch}
import io.janstenpickle.controller.model.event.MacroEvent
import io.janstenpickle.controller.model.event.MacroEvent._

object MacroStatsTranslator {
  val commandType: Command => NonEmptyString = {
    case _: Sleep => NonEmptyString("sleep")
    case _: ToggleSwitch => NonEmptyString("toggle-switch")
    case _: SwitchOn => NonEmptyString("switch-on")
    case _: SwitchOff => NonEmptyString("switch-off")
    case _: Remote => NonEmptyString("remote")
    case _: Macro => NonEmptyString("macro")
  }

  def apply[F[_]](macroEventSubscriber: EventSubscriber[F, MacroEvent]): fs2.Stream[F, Stats] =
    macroEventSubscriber.subscribe.map {
      case ExecutedMacro(name) => Stats.ExecuteMacro(name)
      case ExecutedCommand(command) => Stats.ExecuteCommand(commandType(command))
      case StoredMacroEvent(name, commands) =>
        Stats.StoreMacro(
          name,
          commands
            .groupBy(commandType)
            .view
            .mapValues(_.size)
            .toMap
        )
    }
}
