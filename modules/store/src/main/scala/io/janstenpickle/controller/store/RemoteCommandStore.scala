package io.janstenpickle.controller.store

import cats.Functor
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.model
import io.janstenpickle.controller.model.{RemoteCommandKey, RemoteCommandSource}

trait RemoteCommandStore[F[_], T] {
  def storeCommand(device: NonEmptyString, name: NonEmptyString, payload: T): F[Unit]
  def loadCommand(source: Option[RemoteCommandSource], device: NonEmptyString, name: NonEmptyString): F[Option[T]]
  def listCommands: F[List[RemoteCommandKey]]
}

object RemoteCommandStore {
  def fromConfigSource[F[_]: Functor, T](
    configSource: WritableConfigSource[F, RemoteCommandKey, T],
  ): RemoteCommandStore[F, T] =
    new RemoteCommandStore[F, T] {
      override def storeCommand(device: NonEmptyString, command: NonEmptyString, payload: T): F[Unit] =
        configSource.upsert(model.RemoteCommandKey(None, device, command), payload).void

      override def loadCommand(
        source: Option[RemoteCommandSource],
        device: NonEmptyString,
        command: NonEmptyString
      ): F[Option[T]] =
        configSource.getValue(model.RemoteCommandKey(source, device, command))

      override def listCommands: F[List[RemoteCommandKey]] =
        configSource.listKeys.map(_.toList)
    }
}
