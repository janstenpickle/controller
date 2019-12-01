package io.janstenpickle.controller.tplink

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Error

trait TplinkErrors[F[_]] {
  def decodingFailure[A](device: NonEmptyString, error: Error): F[A]
  def tpLinkCommandTimedOut[A](device: NonEmptyString): F[A]
}
