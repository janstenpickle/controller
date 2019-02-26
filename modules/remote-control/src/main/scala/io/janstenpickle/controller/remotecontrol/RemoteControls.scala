package io.janstenpickle.controller.remotecontrol

import eu.timepit.refined.types.string.NonEmptyString

class RemoteControls[F[_]](remotes: Map[NonEmptyString, RemoteControl[F]])(implicit errors: RemoteControlErrors[F]) {
  private def exec[A](remote: NonEmptyString)(f: RemoteControl[F] => F[A]): F[A] =
    remotes.get(remote).fold[F[A]](errors.missingRemote(remote))(f)

  def send(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
    exec(remote)(_.sendCommand(device, name))

  def learn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
    exec(remote)(_.learn(device, name))
}
