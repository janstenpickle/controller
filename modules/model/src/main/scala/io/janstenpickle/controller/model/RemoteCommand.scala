package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString

case class RemoteCommand(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString)

object RemoteCommand {
  implicit val eq: Eq[RemoteCommand] = semi.eq
}
