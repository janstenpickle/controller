package io.janstenpickle.controller.remotecontrol

import cats.Applicative
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.store.RemoteCommand
import cats.syntax.traverse._
import cats.instances.list._

class RemoteControls[F[_]: Applicative](remotes: Map[NonEmptyString, RemoteControl[F]])(
  implicit errors: RemoteControlErrors[F]
) {
  private def exec[A](remote: NonEmptyString)(f: RemoteControl[F] => F[A]): F[A] =
    remotes.get(remote).fold[F[A]](errors.missingRemote(remote))(f)

  def send(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
    exec(remote)(_.sendCommand(device, name))

  def learn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
    exec(remote)(_.learn(device, name))

  def listCommands: F[List[RemoteCommand]] = remotes.values.toList.flatTraverse(_.listCommands)
}
