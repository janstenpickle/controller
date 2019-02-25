package io.janstenpickle.controller.remotecontrol

import cats.FlatMap
import cats.syntax.flatMap._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.remote.Remote
import io.janstenpickle.controller.store.Store

trait RemoteControl[F[_]] {
  def learn(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def sendCommand(device: NonEmptyString, name: NonEmptyString): F[Unit]
}

object RemoteControl {
  def apply[F[_]: FlatMap](remote: Remote[F], store: Store[F])(
    implicit errors: RemoteControlErrors[F]
  ): RemoteControl[F] = new RemoteControl[F] {
    override def learn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
      remote.learn.flatMap {
        case None => errors.learnFailure(remote.name, device, name)
        case Some(payload) => store.storeCode(remote.name, device, name, payload)
      }

    override def sendCommand(device: NonEmptyString, name: NonEmptyString): F[Unit] =
      store.loadCode(remote.name, device, name).flatMap {
        case None => errors.commandNotFound(remote.name, device, name)
        case Some(payload) => remote.sendCommand(payload)
      }
  }
}
