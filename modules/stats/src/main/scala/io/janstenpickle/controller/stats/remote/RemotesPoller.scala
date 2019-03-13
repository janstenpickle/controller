package io.janstenpickle.controller.stats.remote

import cats.effect.{Concurrent, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import fs2.concurrent.Topic
import io.janstenpickle.controller.configsource.RemoteConfigSource
import io.janstenpickle.controller.stats._

import scala.concurrent.duration.FiniteDuration

object RemotesPoller {
  private val All = NonEmptyString("all")

  def apply[F[_]: Concurrent: Timer](
    pollInterval: FiniteDuration,
    remotes: RemoteConfigSource[F],
    update: Topic[F, Boolean]
  ): Stream[F, Stats] =
    Stream
      .fixedRate(pollInterval)
      .map(_ => true)
      .merge(update.subscribe(1))
      .filter(identity)
      .evalMap(_ => remotes.getRemotes)
      .map { remotes =>
        val roomActivity = remotes.remotes
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
          remotes.remotes.size,
          roomActivity,
          remotes.remotes
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