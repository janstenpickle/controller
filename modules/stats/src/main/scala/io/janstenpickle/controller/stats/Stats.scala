package io.janstenpickle.controller.stats

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{RemoteCommandSource, Room, State}
import io.janstenpickle.controller.switch.model.SwitchKey

sealed trait Stats

object Stats {
  type ButtonType = NonEmptyString
  type CommandType = NonEmptyString

  case object Empty extends Stats

  case class SetActivity(room: Room, activity: NonEmptyString) extends Stats
  case class Activities(
    errorCount: Int,
    activityCount: Map[Room, Int],
    contextButtons: Map[Room, Map[NonEmptyString, Int]]
  ) extends Stats

  case class SendRemoteCommand(
    remote: NonEmptyString,
    commandSource: Option[RemoteCommandSource],
    device: NonEmptyString,
    name: NonEmptyString
  ) extends Stats
  case class LearnRemoteCommand(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString) extends Stats
  case class Remotes(
    errorCount: Int,
    remoteCount: Int,
    remoteRoomActivityCount: Map[Room, Map[NonEmptyString, Int]],
    remoteButtons: Map[NonEmptyString, Map[ButtonType, Int]]
  ) extends Stats

  case class Buttons(errorCount: Int, buttons: Map[Room, Map[ButtonType, Int]]) extends Stats

  case class SwitchOn(device: NonEmptyString, name: NonEmptyString) extends Stats
  case class SwitchOff(device: NonEmptyString, name: NonEmptyString) extends Stats
  case class SwitchToggle(device: NonEmptyString, name: NonEmptyString) extends Stats
  case class SwitchState(key: SwitchKey, state: State) extends Stats

  case class StoreMacro(name: NonEmptyString, commands: Map[CommandType, Int]) extends Stats
  case class ExecuteMacro(name: NonEmptyString) extends Stats
  case class ExecuteCommand(command: CommandType) extends Stats
  case class Macro(name: NonEmptyString, commands: Map[CommandType, Int]) extends Stats
}
