package io.janstenpickle.controller.trace.prometheus

import cats.data.NonEmptyList
import cats.effect.{Resource, Sync}
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.{Applicative, Foldable}
import io.janstenpickle.trace4cats.kernel.SpanExporter
import io.janstenpickle.trace4cats.model.AttributeValue.{BooleanValue, DoubleValue, LongValue, StringValue}
import io.janstenpickle.trace4cats.model.{Batch, CompletedSpan}
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._
import scala.util.Try

object PrometheusSpanExporter {
  final val ServiceNameHeader: String = "x_trace_service"
  final val ParentServiceNameHeader: String = "x_trace_parent_service"

  final val DefaultHistogramBuckets: NonEmptyList[Double] =
    NonEmptyList
      .of(.005, .01, .025, .05, .075, .1, .25, .5, .75, 1, 2.5, 5, 7.5, 10, 12.5, 15)

  private def makeKey(name: String, labels: String*) = (name :: labels.toList).mkString("_")

  private def sanitise(str: String): String = str.replace('.', '_').replace('-', '_').replace("/", "").toLowerCase

  case class Metrics private[prometheus] (
    histograms: ConcurrentHashMap[String, Histogram],
    counters: ConcurrentHashMap[String, Counter],
    gauges: ConcurrentHashMap[String, Gauge]
  )

  def apply[F[_], G[_]: Foldable](
    registry: CollectorRegistry,
    histogramBuckets: NonEmptyList[Double] = DefaultHistogramBuckets
  )(implicit F: Sync[F]) =
    Resource
      .eval(Slf4jLogger.create[F])
      .flatMap { logger =>
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
            def record(span: CompletedSpan): F[Unit] = {
              lazy val sanitisedName = sanitise(span.name)
              lazy val attributes = span.allAttributes

              lazy val stats = attributes.get("stats").fold(true) {
                case BooleanValue(b) => b.value
                case _ => true
              }

              lazy val labels = attributes
                .collect {
                  case (k, StringValue(v)) => sanitise(k) -> v.value
                  case (k, BooleanValue(v)) => sanitise(k) -> v.value.toString
                }
                .updated(ServiceNameHeader, span.serviceName)

              lazy val doubleValues = attributes.collect {
                case (k, LongValue(v)) => k -> v.value.toDouble
                case (k, DoubleValue(v)) => k -> v.value
              }

              lazy val labelKeys = labels.keys.toList
              lazy val labelValues = labels.values.toList

              def recordNumberLabels =
                Sync[F].blocking {
                  doubleValues.foreach {
                    case (label, number) =>
                      val metricName = s"${sanitisedName}_${sanitise(label)}"
                      val key = makeKey(metricName, labelKeys: _*)

                      val gauge = metrics.gauges.getOrDefault(
                        key,
                        Gauge
                          .build(metricName, s"Gauge of numeric label value $label")
                          .labelNames(labelKeys: _*)
                          .create()
                      )

                      Try(gauge.register(registry))
                      metrics.gauges.put(key, gauge)
                      gauge.labels(labelValues: _*).set(number)
                  }
                }

              def recordTime =
                Sync[F]
                  .blocking {
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

                    val seconds = (span.end.toEpochMilli - span.start.toEpochMilli).toDouble / 1000d
                    histogram.labels(labelValues: _*).observe(seconds)
                    counter.labels(labelValues: _*).inc()
                  }

              if (span.name.nonEmpty && stats) (for {
                _ <- recordNumberLabels
                _ <- recordTime
              } yield ()).handleErrorWith { th =>
                logger.warn(th)("Failed to record trace metrics")
              } else Applicative[F].unit
            }

            new SpanExporter[F, G] {
              override def exportBatch(batch: Batch[G]): F[Unit] = batch.spans.traverse_(record)
            }
          }

      }
}
