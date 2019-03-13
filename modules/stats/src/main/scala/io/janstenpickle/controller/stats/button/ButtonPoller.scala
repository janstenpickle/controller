package io.janstenpickle.controller.stats.button

import cats.effect.{Concurrent, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.ButtonConfigSource

import scala.concurrent.duration.FiniteDuration
import fs2.Stream
import fs2.concurrent.Topic
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.stats._

object ButtonPoller {
  private val All: Room = NonEmptyString("all")

  def apply[F[_]: Concurrent: Timer](
    pollInterval: FiniteDuration,
    buttons: ButtonConfigSource[F],
    update: Topic[F, Boolean]
  ): Stream[F, Stats] =
    Stream
      .fixedRate(pollInterval)
      .map(_ => true)
      .merge(update.subscribe(1))
      .filter(identity)
      .evalMap(_ => buttons.getCommonButtons)
      .map { buttons =>
        Stats.Buttons(
          buttons.errors.size,
          buttons.buttons.groupBy(_.room.getOrElse(All)).mapValues(_.groupBy(buttonType).mapValues(_.size))
        )
      }
}
