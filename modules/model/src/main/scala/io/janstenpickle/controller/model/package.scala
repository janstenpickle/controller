package io.janstenpickle.controller

import cats.Eq
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString

package object model {
  implicit val nesEq: Eq[NonEmptyString] = Eq.by(_.value)
}
