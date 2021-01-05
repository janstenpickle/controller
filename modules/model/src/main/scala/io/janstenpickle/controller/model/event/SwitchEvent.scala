package io.janstenpickle.controller.model.event

import io.circe.Codec
import io.circe.generic.extras.semiauto._
import io.circe.refined._
import io.janstenpickle.controller.model.{State, SwitchKey, SwitchMetadata}

sealed trait SwitchEvent {
  def key: SwitchKey
}

object SwitchEvent {
  case class SwitchStateUpdateEvent(key: SwitchKey, state: State, error: Option[String] = None) extends SwitchEvent

  case class SwitchAddedEvent(key: SwitchKey, metadata: SwitchMetadata) extends SwitchEvent

  case class SwitchRemovedEvent(key: SwitchKey) extends SwitchEvent

  implicit val toOption: ToOption[SwitchEvent] = ToOption.instance {
    case _: SwitchRemovedEvent => None
    case e: Any => Some(e)
  }

  implicit val switchEventCodec: Codec[SwitchEvent] = deriveConfiguredCodec
}
