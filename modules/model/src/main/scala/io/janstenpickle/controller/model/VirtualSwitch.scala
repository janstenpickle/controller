package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.list._
import cats.instances.string._
import cats.kernel.Monoid
import eu.timepit.refined.types.string.NonEmptyString

case class VirtualSwitch(remote: NonEmptyString, device: NonEmptyString, command: NonEmptyString)

object VirtualSwitch {
  implicit val eq: Eq[VirtualSwitch] = semi.eq
}

case class VirtualSwitches(virtualSwitches: List[VirtualSwitch] = List.empty, errors: List[String] = List.empty)

object VirtualSwitches {
  implicit val eq: Eq[VirtualSwitches] = semi.eq
  implicit val monoid: Monoid[VirtualSwitches] = semi.monoid
  implicit val setErrors: SetErrors[VirtualSwitches] = SetErrors((switches, errors) => switches.copy(errors = errors))
}
