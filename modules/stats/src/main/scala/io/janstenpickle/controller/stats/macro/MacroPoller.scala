package io.janstenpickle.controller.stats.`macro`

import cats.effect.{Concurrent, Timer}
import cats.syntax.functor._
import eu.timepit.refined.types.numeric.PosInt
import fs2.Stream
import fs2.concurrent.Topic
import io.janstenpickle.controller.stats.Stats
import io.janstenpickle.controller.store.MacroStore

import scala.concurrent.duration.FiniteDuration

object MacroPoller {
  def apply[F[_]: Concurrent: Timer](
    pollInterval: FiniteDuration,
    parallelism: PosInt,
    macros: MacroStore[F],
    update: Topic[F, Boolean]
  ): Stream[F, Stats] =
    Stream
      .fixedRate(pollInterval)
      .map(_ => true)
      .merge(update.subscribe(1))
      .filter(identity)
      .evalMap(_ => macros.listMacros)
      .flatMap(Stream.emits)
      .parEvalMapUnordered(parallelism.value) { m =>
        macros.loadMacro(m).map { commands =>
          Stats.Macro(m, commands.toList.flatMap(_.toList).groupBy(commandType).mapValues(_.size))
        }
      }
}
