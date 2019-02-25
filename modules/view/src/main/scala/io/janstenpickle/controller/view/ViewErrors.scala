package io.janstenpickle.controller.view

import eu.timepit.refined.types.string.NonEmptyString

trait ViewErrors[F[_]] {
  def missingMacro[A](name: NonEmptyString): F[A]
  def missingRemote[A](name: NonEmptyString): F[A]
  def missingSwitch[A](name: NonEmptyString): F[A]
  def missingActivity[A](name: NonEmptyString): F[A]
}
