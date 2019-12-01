package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.string._
import cats.instances.option._
import eu.timepit.refined.types.string.NonEmptyString

case class DiscoveredDeviceValue(name: NonEmptyString, room: Option[NonEmptyString])

object DiscoveredDeviceValue {
  implicit val discoveredDeviceValueEq: Eq[DiscoveredDeviceValue] = semi.eq
}
