package io.janstenpickle.controller.api.service

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.api.validation

trait ConfigServiceErrors[F[_]] {
  def configValidationFailed[A](failures: NonEmptyList[validation.ConfigValidation.ValidationFailure]): F[A]
  def remoteMissing[A](remote: NonEmptyString): F[A]
  def buttonMissing[A](button: NonEmptyString): F[A]
  def activityMissing[A](activity: NonEmptyString): F[A]
  def activityInUse[A](activity: NonEmptyString, remotes: List[NonEmptyString]): F[A]
}
