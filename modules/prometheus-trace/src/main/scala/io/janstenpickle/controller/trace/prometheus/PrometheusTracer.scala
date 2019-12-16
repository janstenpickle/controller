package io.janstenpickle.controller.trace.prometheus

import java.util.concurrent.ConcurrentHashMap

import cats.data.NonEmptyList
import cats.effect.{Blocker, Clock, ContextShift, Resource, Sync}
import cats.syntax.apply._
import cats.syntax.applicativeError._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import natchez.{EntryPoint, Kernel, Span}

import scala.collection.JavaConverters._

object PrometheusTracer {
  final val ServiceNameHeader: String = "x_trace_service"
  final val ParentServiceNameHeader: String = "x_trace_parent_service"

  final val DefaultHistogramBuckets: NonEmptyList[Double] =
    NonEmptyList
      .of(.005, .01, .025, .05, .075, .1, .25, .5, .75, 1, 2.5, 5, 7.5, 10, 12.5, 15)

  case class Metrics private[prometheus] (
    histograms: ConcurrentHashMap[String, Histogram],
    counters: ConcurrentHashMap[String, Counter],
    gauges: ConcurrentHashMap[String, Gauge]
  )

  def entryPoint[F[_]: Clock: ContextShift](
    service: String,
    registry: CollectorRegistry,
    blocker: Blocker,
    histogramBuckets: NonEmptyList[Double] = DefaultHistogramBuckets
  )(implicit F: Sync[F]): Resource[F, EntryPoint[F]] =
    Resource.liftF(Slf4jLogger.create[F]).flatMap { implicit logger =>
      Resource
        .make(
          F.delay(
            Metrics(
              new ConcurrentHashMap[String, Histogram](),
              new ConcurrentHashMap[String, Counter](),
              new ConcurrentHashMap[String, Gauge]()
            )
          )
        ) {
          case Metrics(histograms, counters, gauges) =>
            F.delay(histograms.elements().asScala.foreach(registry.unregister)).recover {
              case _: NullPointerException => ()
            } *> F
              .delay(counters.elements().asScala.foreach(registry.unregister))
              .recover {
                case _: NullPointerException => ()
              } *> F
              .delay(gauges.elements().asScala.foreach(registry.unregister))
              .recover {
                case _: NullPointerException => ()
              } *> F.delay(histograms.clear()) *> F
              .delay(counters.clear()) *> F.delay(gauges.clear())
        }
        .map { metrics =>
          new EntryPoint[F] {
            override def root(name: String): Resource[F, Span[F]] =
              PrometheusSpan
                .makeSpan[F](name, service, None, registry, metrics, blocker, histogramBuckets)

            override def continue(name: String, kernel: Kernel): Resource[F, Span[F]] =
              PrometheusSpan.makeSpan[F](
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
