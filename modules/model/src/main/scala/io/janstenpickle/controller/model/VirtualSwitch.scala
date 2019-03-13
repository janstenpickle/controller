package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.list._
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString

case class VirtualSwitch(remote: NonEmptyString, device: NonEmptyString, command: NonEmptyString)

object VirtualSwitch {
  implicit val eq: Eq[VirtualSwitch] = semi.eq
}

case class VirtualSwitches(virtualSwitches: List[VirtualSwitch] = List.empty, errors: List[String] = List.empty)

object VirtualSwitches {
  implicit val eq: Eq[VirtualSwitches] = semi.eq
}
