package io.janstenpickle.controller.stats.remote

import cats.effect.{Concurrent, Resource}
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import fs2.concurrent.Topic
import io.janstenpickle.controller.stats.{Stats, StatsTopic}
import io.janstenpickle.controller.stats.Stats.{LearnRemoteCommand, SendRemoteCommand}

trait RemoteStatsRecorder[F[_]] {
  def recordSend(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit]
  def recordLearn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit]
}

object RemoteStatsRecorder {
  def stream[F[_]: Concurrent](maxQueued: PosInt): Resource[F, (RemoteStatsRecorder[F], fs2.Stream[F, Stats])] = {
    def make(topic: Topic[F, Stats]): RemoteStatsRecorder[F] = new RemoteStatsRecorder[F] {
      override def recordSend(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        topic.publish1(SendRemoteCommand(remote, device, name))

      override def recordLearn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit] =
        topic.publish1(LearnRemoteCommand(remote, device, name))
    }

    StatsTopic[F](maxQueued).map {
      case (topic, stream) =>
        make(topic) -> stream
    }
  }

}
