package io.janstenpickle.controller.api.service

import cats.data.NonEmptyList
import io.janstenpickle.controller.api.validation

trait ConfigServiceErrors[F[_]] {
  def configValidationFailed[A](failures: NonEmptyList[validation.ConfigValidation.ValidationFailure]): F[A]
}
