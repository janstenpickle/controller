package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString

case class SwitchKey(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString)

object SwitchKey {
  implicit val eq: Eq[SwitchKey] = semi.eq
}
