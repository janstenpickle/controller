package io.janstenpickle.controller.remotecontrol

import cats.instances.list._
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.parallel._
import cats.{FlatMap, Monad, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.store.RemoteCommand

trait RemoteControls[F[_]] {
  def send(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit]
  def learn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit]
  def listCommands: F[List[RemoteCommand]]
  def provides(remote: NonEmptyString): F[Boolean]
}

object RemoteControls {

  def apply[F[_]: Monad: Parallel](
    remotes: Map[NonEmptyString, RemoteControl[F]]
  )(implicit errors: RemoteControlErrors[F]): RemoteControls[F] = new RemoteControls[F] {
    private def exec[A](remote: NonEmptyString)(f: RemoteControl[F] => F[A]): F[A] =
      remotes.get(remote).fold[F[A]](errors.missingRemote(remote))(f)

    def send(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
      exec(remote)(_.sendCommand(device, name))

    def learn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
      exec(remote)(_.learn(device, name))

    def listCommands: F[List[RemoteCommand]] = remotes.values.toList.parFlatTraverse(_.listCommands)

    override def provides(remote: NonEmptyString): F[Boolean] = remotes.contains(remote).pure[F]
  }

  def combined[F[_]: FlatMap](x: RemoteControls[F], y: RemoteControls[F])(
    implicit errors: RemoteControlErrors[F]
  ): RemoteControls[F] = new RemoteControls[F] {
    def choose[A](remote: NonEmptyString, cmd: RemoteControls[F] => F[A]): F[A] =
      x.provides(remote)
        .flatMap[A](if (_) cmd(x) else cmd(y))

    override def send(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
      choose(remote, _.send(remote, device, name))

    override def learn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
      choose(remote, _.learn(remote, device, name))

    override def listCommands: F[List[RemoteCommand]] =
      x.listCommands.map2(y.listCommands)(_ ++ _)

    override def provides(remote: NonEmptyString): F[Boolean] =
      x.provides(remote).map2(y.provides(remote))(_ || _)
  }

}
