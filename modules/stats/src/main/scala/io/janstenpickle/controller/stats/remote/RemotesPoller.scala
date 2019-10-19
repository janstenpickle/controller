package io.janstenpickle.controller.stats.remote

import cats.effect.{Concurrent, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import fs2.concurrent.Topic
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model.Remote
import io.janstenpickle.controller.stats._

import scala.concurrent.duration.FiniteDuration

object RemotesPoller {
  private val All = NonEmptyString("all")

  def apply[F[_]: Concurrent: Timer](
    pollInterval: FiniteDuration,
    remotes: ConfigSource[F, NonEmptyString, Remote],
    update: Topic[F, Boolean]
  ): Stream[F, Stats] =
    Stream
      .fixedRate(pollInterval)
      .map(_ => true)
      .merge(update.subscribe(1))
      .filter(identity)
      .evalMap(_ => remotes.getConfig)
      .map { remotes =>
        val roomActivity = remotes.values.values
          .flatMap { remote =>
            val rooms =
              if (remote.rooms.isEmpty) List(All)
              else remote.rooms

            val activities =
              if (remote.activities.isEmpty) List(All)
              else remote.activities

            rooms.map(_ -> activities.map(_ -> remote.name))
          }
          .groupBy(_._1)
          .mapValues(_.flatMap(_._2.groupBy(_._1).mapValues(_.size)).groupBy(_._1).mapValues(_.map(_._2).sum))

        Stats.Remotes(
          remotes.errors.size,
          remotes.values.size,
          roomActivity,
          remotes.values.values
            .map(
              r =>
                r.name -> r.buttons
                  .groupBy(buttonType)
                  .mapValues(_.size)
            )
            .toMap
        )

      }
}
