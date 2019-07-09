package io.janstenpickle.controller.stats.button

import cats.effect.{Concurrent, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import fs2.concurrent.Topic
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model.{Buttons, Room}
import io.janstenpickle.controller.stats._

import scala.concurrent.duration.FiniteDuration

object ButtonPoller {
  private val All: Room = NonEmptyString("all")

  def apply[F[_]: Concurrent: Timer](
    pollInterval: FiniteDuration,
    buttons: ConfigSource[F, Buttons],
    update: Topic[F, Boolean]
  ): Stream[F, Stats] =
    Stream
      .fixedRate(pollInterval)
      .map(_ => true)
      .merge(update.subscribe(1))
      .filter(identity)
      .evalMap(_ => buttons.getConfig)
      .map { buttons =>
        Stats.Buttons(
          buttons.errors.size,
          buttons.buttons.groupBy(_.room.getOrElse(All)).mapValues(_.groupBy(buttonType).mapValues(_.size))
        )
      }
}
