package io.janstenpickle.controller.store

import cats.Functor
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.model
import io.janstenpickle.controller.model.RemoteCommand

trait RemoteCommandStore[F[_], T] {
  def storeCommand(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString, payload: T): F[Unit]
  def loadCommand(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Option[T]]
  def listCommands: F[List[RemoteCommand]]
}

object RemoteCommandStore {
  def fromConfigSource[F[_]: Functor, T](source: WritableConfigSource[F, RemoteCommand, T]): RemoteCommandStore[F, T] =
    new RemoteCommandStore[F, T] {
      override def storeCommand(
        remote: NonEmptyString,
        device: NonEmptyString,
        name: NonEmptyString,
        payload: T
      ): F[Unit] =
        source.upsert(model.RemoteCommand(remote, device, name), payload).void

      override def loadCommand(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Option[T]] =
        source.getValue(model.RemoteCommand(remote, device, name))

      override def listCommands: F[List[RemoteCommand]] =
        source.getConfig.map(_.values.keys.toList)
    }
}
