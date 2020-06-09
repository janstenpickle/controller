package io.janstenpickle.controller.model.event

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Codec
import io.circe.generic.extras.semiauto._
import io.circe.refined._
import io.janstenpickle.controller.model.Room

case class ActivityUpdateEvent(room: Room, name: NonEmptyString, error: Option[String] = None)

object ActivityUpdateEvent {
  implicit val toOption: ToOption[ActivityUpdateEvent] = ToOption.some

  implicit val activityUpdateEvent: Codec[ActivityUpdateEvent] = deriveConfiguredCodec
}
