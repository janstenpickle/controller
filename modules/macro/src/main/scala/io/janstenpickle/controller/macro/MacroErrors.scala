package io.janstenpickle.controller.`macro`

import eu.timepit.refined.types.string.NonEmptyString

trait MacroErrors[F[_]] {
  def missingMacro[A](name: NonEmptyString): F[A]
}
