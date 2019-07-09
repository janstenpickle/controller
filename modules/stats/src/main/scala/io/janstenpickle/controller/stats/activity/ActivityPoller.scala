package io.janstenpickle.controller.stats.activity

import cats.effect.{Concurrent, Timer}
import fs2.Stream
import fs2.concurrent.Topic
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model.Activities
import io.janstenpickle.controller.stats.Stats

import scala.concurrent.duration.FiniteDuration

object ActivityPoller {
  def apply[F[_]: Concurrent: Timer](
    pollInterval: FiniteDuration,
    activities: ConfigSource[F, Activities],
    update: Topic[F, Boolean]
  ): Stream[F, Stats] =
    Stream
      .fixedRate(pollInterval)
      .map(_ => true)
      .merge(update.subscribe(1))
      .filter(identity)
      .evalMap(_ => activities.getConfig)
      .map { activities =>
        Stats.Activities(
          activities.errors.size,
          activities.activities.groupBy(_.room).mapValues(_.size),
          activities.activities.groupBy(_.room).mapValues(_.map(a => a.name -> a.contextButtons.size).toMap)
        )
      }
}
