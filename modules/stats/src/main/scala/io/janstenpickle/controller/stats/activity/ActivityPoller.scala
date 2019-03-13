package io.janstenpickle.controller.stats.activity

import cats.effect.Timer
import fs2.Stream
import io.janstenpickle.controller.configsource.ActivityConfigSource
import io.janstenpickle.controller.stats.Stats

import scala.concurrent.duration.FiniteDuration

object ActivityPoller {
  def apply[F[_]: Timer](pollInterval: FiniteDuration, activities: ActivityConfigSource[F]): Stream[F, Stats] =
    Stream.fixedRate(pollInterval).evalMap(_ => activities.getActivities).map { activities =>
      Stats.Activities(
        activities.errors.size,
        activities.activities.groupBy(_.room).mapValues(_.size),
        activities.activities.map(a => a.name -> a.contextButtons.size).toMap
      )
    }
}
