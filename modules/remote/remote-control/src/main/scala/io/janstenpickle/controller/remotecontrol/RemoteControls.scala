package io.janstenpickle.controller.remotecontrol

import cats.instances.list._
import cats.kernel.Monoid
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.parallel._
import cats.{FlatMap, Monad, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{RemoteCommand, RemoteCommandSource}
import io.janstenpickle.controller.remotecontrol.RemoteControls.RemoteControlDef

trait RemoteControls[F[_]] {
  def send(
    remote: NonEmptyString,
    commandSource: Option[RemoteCommandSource],
    device: NonEmptyString,
    name: NonEmptyString
  ): F[Unit]
  def learn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit]
  def listCommands: F[List[RemoteCommand]]
  def listRemotes: F[Set[RemoteControlDef]]
  def provides(remote: NonEmptyString): F[Boolean]
}

object RemoteControls { self =>
  case class RemoteControlDef(name: NonEmptyString, supportsLearning: Boolean)

  def apply[F[_]: Monad: Parallel](
    remotes: Map[NonEmptyString, RemoteControl[F]]
  )(implicit errors: RemoteControlErrors[F]): RemoteControls[F] = new RemoteControls[F] {
    private def exec[A](remote: NonEmptyString)(f: RemoteControl[F] => F[A]): F[A] =
      remotes.get(remote).fold[F[A]](errors.missingRemote(remote))(f)

    def send(
      remote: NonEmptyString,
      commandSource: Option[RemoteCommandSource],
      device: NonEmptyString,
      name: NonEmptyString
    ): F[Unit] =
      exec(remote)(_.sendCommand(commandSource, device, name))

    def learn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
      exec(remote)(_.learn(device, name))

    def listCommands: F[List[RemoteCommand]] = remotes.values.toList.parFlatTraverse(_.listCommands)

    override def provides(remote: NonEmptyString): F[Boolean] = remotes.contains(remote).pure[F]

    override def listRemotes: F[Set[RemoteControlDef]] =
      remotes
        .map {
          case (k, remote) => RemoteControlDef(k, remote.supportsLearning)
        }
        .toSet
        .pure[F]
  }

  def apply[F[_]: Monad: Parallel: RemoteControlErrors](remotes: List[RemoteControl[F]]): RemoteControls[F] =
    apply[F](remotes.map { r =>
      r.remoteName -> r
    }.toMap)

  def apply[F[_]: Monad: Parallel: RemoteControlErrors](remote: RemoteControl[F]): RemoteControls[F] =
    apply[F](Map(remote.remoteName -> remote))

  def empty[F[_]: Monad: Parallel: RemoteControlErrors]: RemoteControls[F] =
    apply[F](Map.empty[NonEmptyString, RemoteControl[F]])

  def combined[F[_]: FlatMap](x: RemoteControls[F], y: RemoteControls[F])(
    implicit errors: RemoteControlErrors[F]
  ): RemoteControls[F] = new RemoteControls[F] {
    def choose[A](remote: NonEmptyString, cmd: RemoteControls[F] => F[A]): F[A] =
      x.provides(remote)
        .flatMap[A](if (_) cmd(x) else cmd(y))

    override def send(
      remote: NonEmptyString,
      commandSource: Option[RemoteCommandSource],
      device: NonEmptyString,
      name: NonEmptyString
    ): F[Unit] =
      choose(remote, _.send(remote, commandSource, device, name))

    override def learn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
      choose(remote, _.learn(remote, device, name))

    override def listCommands: F[List[RemoteCommand]] =
      x.listCommands.map2(y.listCommands)(_ ++ _)

    override def provides(remote: NonEmptyString): F[Boolean] =
      x.provides(remote).map2(y.provides(remote))(_ || _)

    override def listRemotes: F[Set[RemoteControlDef]] = x.listRemotes.map2(y.listRemotes)(_ ++ _)
  }

  implicit def remoteControlsMonoid[F[_]: Monad: Parallel: RemoteControlErrors]: Monoid[RemoteControls[F]] =
    new Monoid[RemoteControls[F]] {
      override def empty: RemoteControls[F] = self.empty[F]
      override def combine(x: RemoteControls[F], y: RemoteControls[F]): RemoteControls[F] =
        combined(x, y)
    }

}
