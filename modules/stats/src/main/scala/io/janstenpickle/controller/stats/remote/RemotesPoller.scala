package io.janstenpickle.controller.stats.remote

import cats.effect.Timer
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.janstenpickle.controller.configsource.RemoteConfigSource
import io.janstenpickle.controller.model.Button.{Context, Macro, Remote, Switch}
import io.janstenpickle.controller.stats._

import scala.concurrent.duration.FiniteDuration

object RemotesPoller {
  private val All = NonEmptyString("all")

  def apply[F[_]: Timer](pollInterval: FiniteDuration, remotes: RemoteConfigSource[F]): Stream[F, Stats] =
    Stream.fixedRate(pollInterval).evalMap(_ => remotes.getRemotes).map { remotes =>
      val roomActivity = remotes.remotes
        .flatMap { remote =>
          val rooms =
            if (remote.rooms.isEmpty) List(All)
            else remote.rooms

          val activities =
            if (remote.activities.isEmpty) List(All)
            else remote.activities

          rooms.flatMap(room => activities.map(room -> _)).map(_ -> remote.name)
        }
        .groupBy(_._1)
        .mapValues(_.size)

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
