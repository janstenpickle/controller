package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString

case class RemoteSwitchKey(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString) {
  lazy val toSwitchKey: SwitchKey =
    SwitchKey(NonEmptyString.unsafeFrom(s"$remote-$device"), name)
}

object RemoteSwitchKey {
  implicit val eq: Eq[RemoteSwitchKey] = semi.eq
}
