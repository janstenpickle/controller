package io.janstenpickle.controller.model

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Codec
import io.circe.generic.semiauto._
import io.circe.refined._

case class Rooms(rooms: List[NonEmptyString], errors: List[String])

object Rooms {
  implicit val roomsCodec: Codec.AsObject[Rooms] = deriveCodec
}
