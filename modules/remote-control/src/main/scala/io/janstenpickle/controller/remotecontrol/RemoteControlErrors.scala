package io.janstenpickle.controller.remotecontrol

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.errors.ErrorHandler

trait RemoteControlErrors[F[_]] extends ErrorHandler[F] {
  def commandNotFound[A](remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[A]
  def learnFailure[A](remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[A]
  def missingRemote[A](remote: NonEmptyString): F[A]
  def learningNotSupported[A](remote: NonEmptyString): F[A]
}
