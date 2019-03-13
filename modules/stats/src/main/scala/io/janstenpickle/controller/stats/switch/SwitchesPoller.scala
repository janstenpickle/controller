package io.janstenpickle.controller.stats.switch

import cats.effect.{Concurrent, Timer}
import cats.syntax.functor._
import eu.timepit.refined.types.numeric.PosInt
import fs2.Stream
import io.janstenpickle.controller.stats.Stats
import io.janstenpickle.controller.switch.SwitchProvider

import scala.concurrent.duration.FiniteDuration

object SwitchesPoller {
  def apply[F[_]: Concurrent: Timer](
    pollInterval: FiniteDuration,
    parallelism: PosInt,
    switches: SwitchProvider[F]
  ): Stream[F, Stats] =
    Stream
      .fixedRate(pollInterval)
      .evalMap(_ => switches.getSwitches.map(_.toList))
      .flatMap(Stream.emits)
      .parEvalMapUnordered(parallelism.value) {
        case (key, switch) =>
          switch.getState.map(Stats.SwitchState(key, _))
      }
}
