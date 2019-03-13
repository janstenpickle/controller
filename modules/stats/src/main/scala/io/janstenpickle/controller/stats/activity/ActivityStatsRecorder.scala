package io.janstenpickle.controller.stats.activity

import cats.effect.{Concurrent, Resource}
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import fs2.concurrent.Topic
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.stats.{Stats, StatsTopic}

trait ActivityStatsRecorder[F[_]] {
  def recordSetActivity(room: Room, name: NonEmptyString): F[Unit]
}

object ActivityStatsRecorder {
  def stream[F[_]: Concurrent](maxQueued: PosInt): Resource[F, (ActivityStatsRecorder[F], fs2.Stream[F, Stats])] = {
    def make(topic: Topic[F, Stats]): ActivityStatsRecorder[F] = new ActivityStatsRecorder[F] {
      override def recordSetActivity(room: Room, name: NonEmptyString): F[Unit] =
        topic.publish1(Stats.SetActivity(room, name))
    }

    StatsTopic[F](maxQueued).map {
      case (topic, stream) =>
        make(topic) -> stream
    }
  }
}
