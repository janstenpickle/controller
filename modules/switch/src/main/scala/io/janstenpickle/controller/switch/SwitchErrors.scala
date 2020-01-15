package io.janstenpickle.controller.switch

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.errors.ErrorHandler

trait SwitchErrors[F[_]] { self: ErrorHandler[F] =>
  def missingSwitch[A](device: NonEmptyString, switch: NonEmptyString): F[A]
}
