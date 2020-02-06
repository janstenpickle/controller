package io.janstenpickle.controller.trace.prometheus

import java.util.concurrent.ConcurrentHashMap

import cats.data.NonEmptyList
import cats.effect.{Blocker, Clock, ContextShift, Resource, Sync}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.instances.list._
import cats.syntax.applicativeError._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.chrisdavenport.mapref.MapRef
import io.prometheus.client.{Collector, CollectorRegistry, Counter, Gauge, Histogram}
import natchez.{EntryPoint, Kernel, Span}

import scala.collection.JavaConverters._

object PrometheusTracer {
  final val ServiceNameHeader: String = "x_trace_service"
  final val ParentServiceNameHeader: String = "x_trace_parent_service"

  final val DefaultHistogramBuckets: NonEmptyList[Double] =
    NonEmptyList
      .of(.005, .01, .025, .05, .075, .1, .25, .5, .75, 1, 2.5, 5, 7.5, 10, 12.5, 15)

  case class Metrics[F[_]] private[prometheus] (
    histograms: MapRef[F, String, Option[Histogram]],
    counters: MapRef[F, String, Option[Counter]],
    gauges: MapRef[F, String, Option[Gauge]]
  )

  object Metrics {
    private[prometheus] def apply[F[_]: Sync]: F[Metrics[F]] =
      for {
        histograms <- MapRef.ofScalaConcurrentTrieMap[F, String, Histogram]
        counters <- MapRef.ofScalaConcurrentTrieMap[F, String, Counter]
        gauges <- MapRef.ofScalaConcurrentTrieMap[F, String, Gauge]
      } yield new Metrics(histograms, counters, gauges)

  }

  def entryPoint[F[_]: Clock: ContextShift](
    service: String,
    registry: CollectorRegistry,
    blocker: Blocker,
    prefix: String = "trace",
    histogramBuckets: NonEmptyList[Double] = DefaultHistogramBuckets
  )(implicit F: Sync[F]): Resource[F, EntryPoint[F]] =
    Resource.liftF(Slf4jLogger.create[F]).flatMap { implicit logger =>
      Resource
        .make(Metrics[F]) {
          case Metrics(histograms, counters, gauges) =>
            def unregisterAll[A <: Collector](map: MapRef[F, String, Option[A]]) =
              for {
                keys <- map.keys
                _ <- keys.traverse { key =>
                  map(key).get.flatMap(
                    _.fold(F.unit)(
                      m =>
                        F.delay(registry.unregister(m)).recover {
                          case _: NullPointerException => ()
                      }
                    )
                  )
                }
              } yield ()

            unregisterAll(histograms) >> unregisterAll(counters) >> unregisterAll(gauges)
        }
        .map { metrics =>
          new EntryPoint[F] {
            override def root(name: String): Resource[F, Span[F]] =
              PrometheusSpan
                .makeSpan[F](prefix, name, service, None, registry, metrics, blocker, histogramBuckets)

            override def continue(name: String, kernel: Kernel): Resource[F, Span[F]] =
              PrometheusSpan.makeSpan[F](
                prefix,
                name,
                service,
                kernel.toHeaders.get(ParentServiceNameHeader).orElse(kernel.toHeaders.get(ServiceNameHeader)),
                registry,
                metrics,
                blocker,
                histogramBuckets
              )

            override def continueOrElseRoot(name: String, kernel: Kernel): Resource[F, Span[F]] =
              continue(name, kernel)
          }
        }
    }
}
