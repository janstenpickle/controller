package io.janstenpickle.controller

import cats.Eq
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString

package object model {
  type Room = NonEmptyString

  implicit val nesEq: Eq[NonEmptyString] = Eq.by(_.value)
  val DefaultRoom: Room = NonEmptyString("default")
}
