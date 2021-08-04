package io.janstenpickle.controller.discovery

import cats.effect.{Async, Resource, Sync}
import cats.instances.list._
import cats.instances.set._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{Applicative, Eq, Parallel}
import eu.timepit.refined.types.numeric.PosInt
import fs2.{Pipe, Stream}
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import io.janstenpickle.trace4cats.model.{AttributeValue, SpanStatus}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.FiniteDuration

object DeviceState {
  def apply[F[_]: Sync: Parallel, G[_]: Async, K, V <: DiscoveredDevice[F]](
    deviceType: String,
    pollInterval: FiniteDuration,
    errorCount: PosInt,
    discovery: Discovery[F, K, V],
    onUpdate: Pipe[F, V, Unit],
    k: ResourceKleisli[G, SpanName, Span[G]],
    traceParams: V => List[(String, AttributeValue)] = (_: V) => List.empty
  )(implicit trace: Trace[F], provide: Provide[G, F, Span[G]]): Resource[F, Unit] = {
    implicit val eq: Eq[Map[String, V]] = Eq.by(_.keySet)

    def span[A](name: String)(fa: F[A]): F[A] = trace.span(name)(trace.put("device.type", deviceType) *> fa)

    def deviceState(current: Map[String, V])(implicit logger: Logger[F]): F[Map[String, V]] =
      trace.span(s"device.state") {
        span("read.devices") {
          discovery.devices.map(_.devices.values.toList).handleErrorWith { th =>
            val message = s"Failed to read discovered devices ${current.map(_._2.key).mkString(",")}"
            trace.setStatus(SpanStatus.Internal(s"$message: ${th.getMessage}")) *> logger
              .warn(th)(message)
              .as(List.empty[V])
          }
        }.flatMap(_.parFlatTraverse { device =>
            span("read.device") {
              (for {
                _ <- trace.putAll(traceParams(device): _*)
                _ <- span("refresh.device") {
                  device.refresh
                }
                key <- device.updatedKey
                _ <- trace.put("key", key)
              } yield List(key -> device)).handleErrorWith { th =>
                val message = s"Failed to refresh device ${device.key}"
                trace.setStatus(SpanStatus.Internal(s"$message: ${th.getMessage}")) *> logger
                  .warn(th)(message)
                  .as(List.empty[(String, V)])
              }
            }
          })
          .flatMap { devs =>
            val devMap = devs.toMap

            Stream
              .fromIterator[F](current.view.filterKeys(!devMap.keySet.contains(_)).values.iterator, current.size)
              .through(onUpdate)
              .compile
              .drain
              .as(devMap)
          }
      }

    Resource.eval(Slf4jLogger.fromName[F](s"deviceState-$deviceType")).flatMap { implicit logger =>
      DataPoller.traced[F, G, Map[String, V], Unit]("device.state", "device.type" -> deviceType)(
        (current: Data[Map[String, V]]) => deviceState(current.value),
        pollInterval,
        errorCount,
        (_: Map[String, V], _: Map[String, V]) => Applicative[F].unit,
        k,
      ) { (_, _) =>
        ()
      }
    }
  }
}
