package io.janstenpickle.controller.store

import eu.timepit.refined.types.string.NonEmptyString

trait RemoteCommandStore[F[_], T] {
  def storeCode(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString, payload: T): F[Unit]
  def loadCode(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Option[T]]
}
