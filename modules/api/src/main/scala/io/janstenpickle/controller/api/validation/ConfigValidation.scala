package io.janstenpickle.controller.api.validation

import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.{Monad, NonEmptyParallel, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.store.MacroStore
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model
import io.janstenpickle.controller.model.{Activity, Button, ContextButtonMapping, Remote, RemoteCommand, SwitchKey}
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.switch.Switches
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.AttributeValue

class ConfigValidation[F[_]: Monad: NonEmptyParallel](
  activitySource: ConfigSource[F, String, Activity],
  remoteControls: RemoteControls[F],
  macros: MacroStore[F],
  switches: Switches[F]
)(implicit trace: Trace[F]) {
  import ConfigValidation._

  private def conditionalFailure(cond: Boolean)(error: ValidationFailure): List[ValidationFailure] =
    if (cond) List(error) else List.empty

  private def traceErrors(
    fields: (String, AttributeValue)*
  )(fe: F[List[ValidationFailure]]): F[List[ValidationFailure]] =
    trace.putAll(fields: _*) *> fe.flatTap { errors =>
      trace.putAll("error" -> errors.nonEmpty, "error.count" -> errors.size)
    }

  private def validateContextButtons(buttons: List[ContextButtonMapping])(
    remoteCommands: List[RemoteCommand],
    switches: Set[SwitchKey],
    macros: List[NonEmptyString]
  ): List[ValidationFailure] =
    buttons.flatMap {
      case ContextButtonMapping.Remote(_, remote, commandSource, device, command) =>
        val cmd = model.RemoteCommand(remote, commandSource, device, command)
        conditionalFailure(!remoteCommands.contains(cmd))(ValidationFailure.RemoteCommandNotFound(cmd))
      case ContextButtonMapping.ToggleSwitch(_, device, switch) =>
        val key = SwitchKey(device, switch)
        conditionalFailure(!switches.contains(key))(ValidationFailure.SwitchNotFound(key))
      case ContextButtonMapping.Macro(_, m) =>
        conditionalFailure(!macros.contains(m))(ValidationFailure.MacroNotFound(m))
    }

  def validateActivity(activity: Activity): F[List[ValidationFailure]] = trace.span("validate.activity") {
    traceErrors(
      "activity.name" -> activity.name.value,
      "activity.label" -> activity.label.value,
      "activity.room" -> activity.room.value
    )(
      Parallel
        .parMap3(remoteControls.listCommands, switches.list, macros.listMacros) { (cmds, sws, ms) =>
          validateContextButtons(activity.contextButtons)(cmds, sws, ms) ++ conditionalFailure(
            !ms.contains(NonEmptyString.unsafeFrom(s"${activity.room.value}-${activity.name.value}"))
          )(ValidationFailure.MacroNotFound(activity.name))
        }
    )
  }

  private def validateButtons(buttons: List[Button])(
    remoteCommands: List[RemoteCommand],
    switches: Set[SwitchKey],
    macros: List[NonEmptyString]
  ): List[ValidationFailure] =
    buttons.flatMap {
      case button: Button.Remote =>
        val cmd = model.RemoteCommand(button.remote, button.commandSource, button.device, button.name)
        conditionalFailure(!remoteCommands.contains(cmd))(ValidationFailure.RemoteCommandNotFound(cmd))
      case button: Button.Switch =>
        val key = SwitchKey(button.device, button.name)
        conditionalFailure(!switches.contains(key))(ValidationFailure.SwitchNotFound(key))
      case button: Button.Macro =>
        conditionalFailure(!macros.contains(button.name))(ValidationFailure.MacroNotFound(button.name))
      case _: Button.Context => List.empty // context buttons can't be validated
    }

  def validateRemote(remote: Remote): F[List[ValidationFailure]] = trace.span("validate.remote") {
    traceErrors("remote.name" -> remote.name.value)(
      Parallel
        .parMap4(activitySource.getConfig, macros.listMacros, remoteControls.listCommands, switches.list) {
          (activities, ms, commands, sws) =>
            val activityDiff = remote.activities.diff(activities.values.values.map(_.name).toSet)

            validateButtons(remote.buttons)(commands, sws, ms) ++ conditionalFailure(activityDiff.nonEmpty)(
              ValidationFailure.ActivitiesNotFound(activityDiff)
            )
        }
    )
  }

  def validateButton(button: Button): F[List[ValidationFailure]] = trace.span("validate.button") {
    traceErrors("button.name" -> button.name.value)(
      Parallel.parMap3(remoteControls.listCommands, switches.list, macros.listMacros)(validateButtons(List(button)))
    )
  }

}

object ConfigValidation {
  sealed trait ValidationFailure
  object ValidationFailure {
    case class ActivitiesNotFound(activities: Set[NonEmptyString]) extends ValidationFailure
    case class RemoteCommandNotFound(command: RemoteCommand) extends ValidationFailure
    case class MacroNotFound(name: NonEmptyString) extends ValidationFailure
    case class SwitchNotFound(key: SwitchKey) extends ValidationFailure
  }
}
