package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Codec
import io.circe.generic.semiauto._
import io.circe.refined._

case class RemoteCommand(
  remote: NonEmptyString,
  source: Option[RemoteCommandSource],
  device: NonEmptyString,
  name: NonEmptyString
)

object RemoteCommand {
  implicit val eq: Eq[RemoteCommand] = semi.eq
  implicit val remoteCommandCodec: Codec.AsObject[RemoteCommand] = deriveCodec
}
