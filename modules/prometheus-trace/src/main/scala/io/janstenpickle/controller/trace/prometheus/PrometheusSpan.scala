package io.janstenpickle.controller.trace.prometheus

import java.util.concurrent.TimeUnit

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.concurrent.Ref
import cats.effect.{Blocker, Clock, ContextShift, Resource, Sync}
import cats.instances.list._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import io.chrisdavenport.log4cats.Logger
import io.janstenpickle.controller.trace.prometheus.PrometheusTracer._
import io.prometheus.client.{Collector, CollectorRegistry, Counter, Gauge, Histogram}
import natchez.TraceValue.{BooleanValue, NumberValue, StringValue}
import natchez.{Kernel, Span, TraceValue}

import scala.collection.JavaConverters._

private[prometheus] final case class PrometheusSpan[F[_]: Sync: Clock: ContextShift: Logger](
  prefix: String,
  serviceName: String,
  parentService: Option[String],
  registry: CollectorRegistry,
  metrics: Metrics[F],
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
    PrometheusSpan.makeSpan[F](prefix, name, serviceName, parentService, registry, metrics, blocker, histogramBuckets)
}

object PrometheusSpan {
  private def sanitise(str: String): String = str.replace('.', '_').replace('-', '_').replace("/", "").toLowerCase

  def makeSpan[F[_]: Sync: ContextShift: Logger](
    prefix: String,
    name: String,
    serviceName: String,
    parentService: Option[String],
    registry: CollectorRegistry,
    metrics: Metrics[F],
    blocker: Blocker,
    histogramBuckets: NonEmptyList[Double]
  )(implicit clock: Clock[F]): Resource[F, Span[F]] = {
    val sanitisedName = sanitise(s"${prefix}_$name")

    def updateCollector[A <: Collector](ref: Ref[F, Option[A]], make: => A, f: A => Unit, labels: List[String]) =
      for {
        maybeA <- ref.get
        a <- maybeA.fold {
          val a = make
          blocker.delay(registry.register(a)).flatMap(_ => ref.set(Some(a))).as(a)
        }(_.pure[F])
        _ <- blocker
          .delay(f(a))
          .handleErrorWith(
            Logger[F].error(_)(s"Failed to record metric '$sanitisedName' with labels '${labels.mkString(",")}''")
          )
      } yield ()

    def recordNumberLabels(labelKeys: List[String], labelValues: List[String], numberValues: Map[String, Number]) =
      numberValues.toList.traverse {
        case (label, number) =>
          val metricName = s"${sanitisedName}_${sanitise(label)}"

          updateCollector[Gauge](
            metrics.gauges(sanitisedName),
            Gauge.build(metricName, s"Gauge of numeric label value $label").labelNames(labelKeys: _*).create(),
            _.labels(labelValues: _*).set(number.doubleValue()),
            labelValues
          )
      }

    def recordTime(labelKeys: List[String], labelValues: List[String], start: Long, end: Long) = {
      lazy val seconds = (end - start).toDouble / 1000d

      updateCollector[Histogram](
        metrics.histograms(sanitisedName),
        Histogram
          .build(s"${sanitisedName}_seconds", "Histogram of time from span")
          .labelNames(labelKeys: _*)
          .buckets(histogramBuckets.toList: _*)
          .create(),
        _.labels(labelValues: _*).observe(seconds),
        labelValues
      ) >> updateCollector[Counter](
        metrics.counters(sanitisedName),
        Counter.build(s"${sanitisedName}_total", "Count from span").labelNames(labelKeys: _*).create(),
        _.labels(labelValues: _*).inc(),
        labelValues
      )
    }

    Resource
      .make(for {
        start <- clock.realTime(TimeUnit.MILLISECONDS)
        labelsRef <- Ref.of(Map.empty[String, TraceValue])
      } yield (start, labelsRef)) {
        case (start, labelsRef) =>
          labelsRef.get.flatMap { traceLabels =>
            val stats = traceLabels.get("stats").fold(true) {
              case BooleanValue(b) => b
              case _ => true
            }

            if (name.nonEmpty && stats) (for {
              end <- clock.realTime(TimeUnit.MILLISECONDS)

              labels = traceLabels
                .collect {
                  case (k, StringValue(v)) => sanitise(k) -> v
                  case (k, BooleanValue(v)) => sanitise(k) -> v.toString
                }
                .updated(ServiceNameHeader, serviceName) ++ parentService.map(ParentServiceNameHeader -> _)

              numbers = traceLabels.collect { case (k, NumberValue(v)) => k -> v }

              labelKeys = labels.keys.toList
              labelValues = labels.values.toList

              _ = println(s"$sanitisedName => $labels")
              _ <- recordNumberLabels(labelKeys, labelValues, numbers)
              _ <- recordTime(labelKeys, labelValues, start, end)
            } yield ()).handleErrorWith { th =>
              Logger[F].warn(th)("Failed to record trace metrics")
            } else Applicative[F].unit
          }
      }
      .map {
        case (_, labelsRef) =>
          PrometheusSpan(prefix, serviceName, parentService, registry, metrics, blocker, labelsRef, histogramBuckets)
      }
  }
}
