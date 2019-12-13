package io.janstenpickle.controller.trace.prometheus

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.concurrent.Ref
import cats.effect.{Blocker, Clock, ContextShift, Resource, Sync}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.chrisdavenport.log4cats.Logger
import io.janstenpickle.controller.trace.prometheus.PrometheusTracer._
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import natchez.TraceValue.{BooleanValue, NumberValue, StringValue}
import natchez.{Kernel, Span, TraceValue}

import scala.util.Try

private[prometheus] final case class PrometheusSpan[F[_]: Sync: Clock: ContextShift: Logger](
  serviceName: String,
  parentService: Option[String],
  registry: CollectorRegistry,
  metrics: Metrics,
  blocker: Blocker,
  labelsRef: Ref[F, Map[String, TraceValue]],
  histogramBuckets: NonEmptyList[Double]
) extends Span[F] {
  override def put(fields: (String, TraceValue)*): F[Unit] =
    labelsRef.update(_ ++ fields)

  override def kernel: F[Kernel] = labelsRef.get.map { labels =>
    Kernel(
      labels.mapValues(_.value.toString).updated(ServiceNameHeader, serviceName) ++ parentService
        .map(ParentServiceNameHeader -> _)
    )
  }

  override def span(name: String): Resource[F, Span[F]] =
    PrometheusSpan.makeSpan[F](name, serviceName, parentService, registry, metrics, blocker, histogramBuckets)
}

object PrometheusSpan {
  private final val allName: String = "span"
  private final val spanDimension: String = "span_name"

  private def makeKey(name: String, labels: String*) = (name :: labels.toList).mkString("_")

  private def sanitise(str: String): String = str.replace('.', '_').replace('-', '_').replace("/", "").toLowerCase

  def makeSpan[F[_]: Sync: ContextShift: Logger](
    name: String,
    serviceName: String,
    parentService: Option[String],
    registry: CollectorRegistry,
    metrics: Metrics,
    blocker: Blocker,
    histogramBuckets: NonEmptyList[Double]
  )(implicit clock: Clock[F]): Resource[F, Span[F]] = {
    val sanitisedName = sanitise(name)

    def recordNumberLabels(labelKeys: List[String], labelValues: List[String], numberValues: Map[String, Number]) =
      blocker
        .delay {
          numberValues.foreach {
            case (label, number) =>
              val metricName = s"${sanitisedName}_${sanitise(label)}"
              val key = makeKey(metricName, labelKeys: _*)

              val gauge = metrics.gauges.getOrDefault(
                key,
                Gauge.build(metricName, s"Gauge of numeric label value $label").labelNames(labelKeys: _*).create()
              )

              Try(gauge.register(registry))
              metrics.gauges.put(key, gauge)
              gauge.labels(labelValues: _*).set(number.doubleValue())
          }
        }

    def recordTime(labelKeys: List[String], labelValues: List[String], start: Long, end: Long) =
      blocker
        .delay {
          val key = makeKey(sanitisedName, labelKeys: _*)

          val histogram =
            metrics.histograms.getOrDefault(
              key,
              Histogram
                .build(s"${sanitisedName}_seconds", "Histogram of time from span")
                .labelNames(labelKeys: _*)
                .buckets(histogramBuckets.toList: _*)
                .create()
            )

          val counter =
            metrics.counters.getOrDefault(
              key,
              Counter.build(s"${sanitisedName}_total", "Count from span").labelNames(labelKeys: _*).create()
            )
          // doesn't matter if it throws an error saying already registered
          Try(histogram.register(registry))
          metrics.histograms.put(key, histogram)
          Try(counter.register(registry))
          metrics.counters.put(key, counter)

          val seconds = (end - start).toDouble / 1000d
          histogram.labels(labelValues: _*).observe(seconds)
          counter.labels(labelValues: _*).inc()
        }

    Resource
      .make(for {
        start <- clock.realTime(TimeUnit.MILLISECONDS)
        labelsRef <- Ref.of(Map.empty[String, TraceValue])
      } yield (start, labelsRef)) {
        case (start, labelsRef) =>
          if (name.nonEmpty) (for {
            end <- clock.realTime(TimeUnit.MILLISECONDS)
            traceLabels <- labelsRef.get

            labels = traceLabels
              .collect {
                case (k, StringValue(v)) => sanitise(k) -> v
                case (k, BooleanValue(v)) => sanitise(k) -> v.toString
              }
              .updated(ServiceNameHeader, serviceName) ++ parentService.map(ParentServiceNameHeader -> _)

            numbers = traceLabels.collect { case (k, NumberValue(v)) => k -> v }

            labelKeys = labels.keys.toList
            labelValues = labels.values.toList

            _ <- recordNumberLabels(labelKeys, labelValues, numbers)
            _ <- recordTime(labelKeys, labelValues, start, end)
          } yield ()).handleErrorWith { th =>
            Logger[F].warn(th)("Failed to record trace metrics")
          } else Applicative[F].unit
      }
      .map {
        case (_, labelsRef) =>
          PrometheusSpan(serviceName, parentService, registry, metrics, blocker, labelsRef, histogramBuckets)
      }
  }
}
