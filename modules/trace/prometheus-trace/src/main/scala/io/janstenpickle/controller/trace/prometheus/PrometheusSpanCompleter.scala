package io.janstenpickle.controller.trace.prometheus

import cats.data.NonEmptyList
import cats.effect.Resource
import cats.effect.kernel.Async
import fs2.Chunk
import io.janstenpickle.controller.trace.prometheus.PrometheusSpanExporter.DefaultHistogramBuckets
import io.janstenpickle.trace4cats.`export`.{CompleterConfig, QueuedSpanCompleter}
import io.janstenpickle.trace4cats.kernel.SpanCompleter
import io.janstenpickle.trace4cats.model.TraceProcess
import io.prometheus.client.CollectorRegistry
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object PrometheusSpanCompleter {
  def apply[F[_]: Async](
    registry: CollectorRegistry,
    process: TraceProcess,
    histogramBuckets: NonEmptyList[Double] = DefaultHistogramBuckets,
    config: CompleterConfig = CompleterConfig()
  ): Resource[F, SpanCompleter[F]] =
    for {
      implicit0(logger: Logger[F]) <- Resource.eval(Slf4jLogger.create[F])
      exporter <- PrometheusSpanExporter[F, Chunk](registry)
      completer <- QueuedSpanCompleter[F](process, exporter, config)
    } yield completer
}
