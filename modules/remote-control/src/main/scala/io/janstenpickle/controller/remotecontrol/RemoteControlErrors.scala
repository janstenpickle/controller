package io.janstenpickle.controller.remotecontrol

import eu.timepit.refined.types.string.NonEmptyString

trait RemoteControlErrors[F[_]] {
  def commandNotFound[A](remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[A]
  def learnFailure[A](remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[A]
}
