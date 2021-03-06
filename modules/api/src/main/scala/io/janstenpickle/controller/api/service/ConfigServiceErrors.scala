package io.janstenpickle.controller.api.service

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.api.validation
import io.janstenpickle.controller.errors.ErrorHandler

trait ConfigServiceErrors[F[_]] { self: ErrorHandler[F] =>
  def configValidationFailed[A](failures: NonEmptyList[validation.ConfigValidation.ValidationFailure]): F[A]
  def remoteAlreadyExists[A](remote: NonEmptyString): F[A]
  def remoteMissing[A](remote: NonEmptyString): F[A]
  def buttonMissing[A](button: String): F[A]
  def buttonAlreadyExists[A](button: NonEmptyString): F[A]
  def activityMissing[A](activity: NonEmptyString): F[A]
  def activityInUse[A](activity: NonEmptyString, remotes: List[NonEmptyString]): F[A]
  def activityAlreadyExists[A](room: NonEmptyString, name: NonEmptyString): F[A]
}
