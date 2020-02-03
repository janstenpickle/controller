package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import eu.timepit.refined.types.string.NonEmptyString

case class SwitchKey(device: NonEmptyString, name: NonEmptyString)

object SwitchKey {
  implicit val eq: Eq[SwitchKey] = semi.eq
}
