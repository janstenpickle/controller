package io.janstenpickle.controller.switch

import eu.timepit.refined.types.string.NonEmptyString

trait SwitchErrors[F[_]] {
  def missingSwitch[A](switch: NonEmptyString): F[A]
}
