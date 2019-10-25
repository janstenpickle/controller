package io.janstenpickle.controller.stats.remote

import cats.Apply
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{RemoteCommand, RemoteCommandSource}
import io.janstenpickle.controller.remotecontrol.RemoteControls

object RemoteControlsStats {
  def apply[F[_]: Apply](underlying: RemoteControls[F])(implicit stats: RemoteStatsRecorder[F]): RemoteControls[F] =
    new RemoteControls[F] {
      override def send(
        remote: NonEmptyString,
        commandSource: Option[RemoteCommandSource],
        device: NonEmptyString,
        name: NonEmptyString
      ): F[Unit] =
        underlying.send(remote, commandSource, device, name) *> stats.recordSend(remote, commandSource, device, name)

      override def learn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        underlying.learn(remote, device, name) *> stats.recordLearn(remote, device, name)

      override def listCommands: F[List[RemoteCommand]] = underlying.listCommands
      override def provides(remote: NonEmptyString): F[Boolean] = underlying.provides(remote)
    }
}
