package io.janstenpickle.controller.stats

import cats.effect.{Concurrent, Resource}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.effect.syntax.concurrent._
import eu.timepit.refined.types.numeric.PosInt
import fs2.concurrent.{Queue, Topic}

object StatsTopic {
  private[stats] def apply[F[_]: Concurrent](maxQueued: PosInt): Resource[F, (Topic[F, Stats], fs2.Stream[F, Stats])] =
    Resource
      .make(for {
        topic <- Topic[F, Stats](Stats.Empty)
        queue <- Queue.circularBuffer[F, Stats](maxQueued.value)
        fiber <- topic.subscribe(maxQueued.value).through(queue.enqueue).compile.drain.start
      } yield (topic, queue.dequeue, fiber)) { case (_, _, fiber) => fiber.cancel }
      .map { case (topic, stream, _) => (topic, stream) }
}
