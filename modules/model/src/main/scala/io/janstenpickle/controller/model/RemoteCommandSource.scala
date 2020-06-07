package io.janstenpickle.controller.model

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Codec
import io.circe.generic.semiauto._
import io.circe.refined._

case class RemoteCommandSource(name: NonEmptyString, `type`: NonEmptyString)

object RemoteCommandSource {
  implicit val remoteCommandSourceCodec: Codec.AsObject[RemoteCommandSource] = deriveCodec
}
