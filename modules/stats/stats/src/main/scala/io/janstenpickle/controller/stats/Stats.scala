package io.janstenpickle.controller.stats

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{RemoteCommandSource, Room, State, SwitchKey, SwitchType}

sealed trait Stats

object Stats {
  type ButtonType = NonEmptyString
  type CommandType = NonEmptyString

  case object Empty extends Stats

  case class SetActivity(room: Room, activity: NonEmptyString) extends Stats
  case class ActivityError(room: Room, activity: NonEmptyString) extends Stats
  case class Activities(activityCount: Map[Room, Int], contextButtons: Map[Room, Map[NonEmptyString, Int]])
      extends Stats

  case class SendRemoteCommand(
    remote: NonEmptyString,
    commandSource: Option[RemoteCommandSource],
    device: NonEmptyString,
    name: NonEmptyString
  ) extends Stats
  case class LearnRemoteCommand(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString) extends Stats
  case class RemoteDevices(deviceCount: Int) extends Stats
  case class Remotes(
    remoteCount: Int,
    remoteRoomActivityCount: Map[Room, Map[NonEmptyString, Int]],
    remoteButtons: Map[NonEmptyString, Map[ButtonType, Int]]
  ) extends Stats

  case class Buttons(buttons: Map[Room, Map[ButtonType, Int]]) extends Stats

  case class SwitchOn(device: NonEmptyString, name: NonEmptyString) extends Stats
  case class SwitchOff(device: NonEmptyString, name: NonEmptyString) extends Stats
  case class SwitchState(key: SwitchKey, state: State) extends Stats
  case class SwitchError(device: NonEmptyString, name: NonEmptyString) extends Stats
  case class Switches(
    switchCount: Int,
    switchType: Map[SwitchType, Int],
    switchRoom: Map[String, Int],
    switchDevice: Map[NonEmptyString, Int]
  ) extends Stats

  case class StoreMacro(name: NonEmptyString, commands: Map[CommandType, Int]) extends Stats
  case class ExecuteMacro(name: NonEmptyString) extends Stats
  case class ExecuteCommand(command: CommandType) extends Stats
  case class Macros(macroCount: Int, commands: Map[NonEmptyString, Int]) extends Stats
}
