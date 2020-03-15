package io.janstenpickle.controller.model.event

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{
  Activity,
  Button,
  Command,
  MultiSwitch,
  Remote,
  RemoteSwitchKey,
  Room,
  VirtualSwitch
}

sealed trait ConfigEvent {
  def source: String
}

object ConfigEvent {
  case class ActivityAddedEvent(activity: Activity, source: String) extends ConfigEvent

  case class ActivityRemovedEvent(room: Room, activity: NonEmptyString, source: String) extends ConfigEvent

  case class ButtonAddedEvent(button: Button, source: String) extends ConfigEvent

  case class ButtonRemovedEvent(name: NonEmptyString, room: Option[Room], source: String) extends ConfigEvent

  case class MacroAddedEvent(name: NonEmptyString, commands: NonEmptyList[Command], source: String) extends ConfigEvent

  case class MacroRemovedEvent(name: NonEmptyString, source: String) extends ConfigEvent

  case class MultiSwitchAddedEvent(multiSwitch: MultiSwitch, source: String) extends ConfigEvent

  case class MultiSwitchRemovedEvent(name: NonEmptyString, source: String) extends ConfigEvent

  case class VirtualSwitchAddedEvent(key: RemoteSwitchKey, virtualSwitch: VirtualSwitch, source: String)
      extends ConfigEvent

  case class VirtualSwitchRemovedEvent(key: RemoteSwitchKey, source: String) extends ConfigEvent

  case class RemoteAddedEvent(remote: Remote, source: String) extends ConfigEvent

  case class RemoteRemovedEvent(name: NonEmptyString, source: String) extends ConfigEvent

  implicit val toOption: ToOption[ConfigEvent] = ToOption.instance {
    case _: ActivityRemovedEvent => None
    case _: ButtonRemovedEvent => None
    case _: MacroRemovedEvent => None
    case _: MultiSwitchRemovedEvent => None
    case _: VirtualSwitchRemovedEvent => None
    case _: RemoteRemovedEvent => None
    case e: Any => Some(e)
  }
}
