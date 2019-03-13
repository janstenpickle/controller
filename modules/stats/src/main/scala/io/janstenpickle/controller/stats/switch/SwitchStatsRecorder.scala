package io.janstenpickle.controller.stats.switch

import cats.effect.{Concurrent, Resource}
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import fs2.concurrent.Topic
import io.janstenpickle.controller.stats.{Stats, StatsTopic}

trait SwitchStatsRecorder[F[_]] {
  def recordSwitchOn(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def recordSwitchOff(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def recordToggle(device: NonEmptyString, name: NonEmptyString): F[Unit]
}

object SwitchStatsRecorder {
  def stream[F[_]: Concurrent](maxQueued: PosInt): Resource[F, (SwitchStatsRecorder[F], fs2.Stream[F, Stats])] = {
    def make(topic: Topic[F, Stats]) = new SwitchStatsRecorder[F] {
      override def recordSwitchOn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        topic.publish1(Stats.SwitchOn(device, name))

      override def recordSwitchOff(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        topic.publish1(Stats.SwitchOff(device, name))

      override def recordToggle(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        topic.publish1(Stats.SwitchToggle(device, name))
    }

    StatsTopic[F](maxQueued).map {
      case (topic, stream) =>
        make(topic) -> stream
    }
  }
}
