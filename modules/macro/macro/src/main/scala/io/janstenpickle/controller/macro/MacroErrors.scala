package io.janstenpickle.controller.`macro`

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.errors.ErrorHandler

trait MacroErrors[F[_]] { self: ErrorHandler[F] =>
  def missingMacro[A](name: NonEmptyString): F[A]
  def macroAlreadyExists[A](name: NonEmptyString): F[A]
}
