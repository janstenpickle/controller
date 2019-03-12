package io.janstenpickle.controller.switch.model

import cats.Eq
import cats.derived.semi
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model._

case class SwitchKey(device: NonEmptyString, name: NonEmptyString)

object SwitchKey {
  implicit val eq: Eq[SwitchKey] = semi.eq
}
