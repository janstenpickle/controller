package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString

case class RemoteCommandKey(source: Option[RemoteCommandSource], device: NonEmptyString, name: NonEmptyString)

object RemoteCommandKey {
  implicit val eq: Eq[RemoteCommandKey] = semi.eq
}
