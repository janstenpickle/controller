package io.janstenpickle.controller.store

import eu.timepit.refined.types.string.NonEmptyString

trait RemoteCommandStore[F[_], T] {
  def storeCommand(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString, payload: T): F[Unit]
  def loadCommand(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Option[T]]
  def listCommands: F[List[RemoteCommand]]
}
