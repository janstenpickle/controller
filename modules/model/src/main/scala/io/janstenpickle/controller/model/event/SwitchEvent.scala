package io.janstenpickle.controller.model.event

import io.janstenpickle.controller.model.{State, SwitchKey, SwitchMetadata}

sealed trait SwitchEvent {
  def key: SwitchKey
}

object SwitchEvent {
  case class SwitchStateUpdateEvent(key: SwitchKey, state: State, error: Option[Throwable] = None) extends SwitchEvent

  case class SwitchAddedEvent(key: SwitchKey, metadata: SwitchMetadata) extends SwitchEvent

  case class SwitchRemovedEvent(key: SwitchKey) extends SwitchEvent

  implicit val toOption: ToOption[SwitchEvent] = ToOption.instance {
    case _: SwitchRemovedEvent => None
    case e: Any => Some(e)
  }
}
