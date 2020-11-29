package io.janstenpickle.controller.trace.prometheus

import cats.data.NonEmptyList
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import fs2.Chunk
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.trace.prometheus.PrometheusSpanExporter.DefaultHistogramBuckets
import io.janstenpickle.trace4cats.`export`.QueuedSpanCompleter
import io.janstenpickle.trace4cats.kernel.SpanCompleter
import io.janstenpickle.trace4cats.model.TraceProcess
import io.prometheus.client.CollectorRegistry

import scala.concurrent.duration._

object PrometheusSpanCompleter {
  def apply[F[_]: Concurrent: ContextShift: Timer](
    registry: CollectorRegistry,
    blocker: Blocker,
    process: TraceProcess,
    histogramBuckets: NonEmptyList[Double] = DefaultHistogramBuckets,
    bufferSize: Int = 2000,
    batchSize: Int = 50,
    batchTimeout: FiniteDuration = 10.seconds
  ): Resource[F, SpanCompleter[F]] =
    for {
      implicit0(logger: Logger[F]) <- Resource.liftF(Slf4jLogger.create[F])
      exporter <- PrometheusSpanExporter[F, Chunk](registry, blocker)
      completer <- QueuedSpanCompleter[F](process, exporter, bufferSize, batchSize, batchTimeout)
    } yield completer
}
