package io.janstenpickle.controller.discovery

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.list._
import cats.instances.set._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{Applicative, Eq, Parallel}
import eu.timepit.refined.types.numeric.PosInt
import fs2.{Pipe, Stream}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data
import natchez.{Trace, TraceValue}

import scala.concurrent.duration.FiniteDuration

object DeviceState {
  def apply[F[_]: Sync: Parallel, G[_]: Concurrent: Timer, K, V <: DiscoveredDevice[F]](
    deviceType: String,
    pollInterval: FiniteDuration,
    errorCount: PosInt,
    discovery: Discovery[F, K, V],
    onUpdate: Pipe[F, V, Unit],
    traceParams: V => List[(String, TraceValue)] = (_: V) => List.empty
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, Unit] = {
    implicit val eq: Eq[Map[String, V]] = Eq.by(_.keySet)

    def span[A](name: String)(fa: F[A]): F[A] = trace.span(name)(trace.put("device.type" -> deviceType) *> fa)

    def deviceState(current: Map[String, V]): F[Map[String, V]] = trace.span(s"device.state") {
      span("read.devices") {
        discovery.devices
      }.flatMap(_.devices.values.toList.parTraverse { device =>
          span("read.device") {
            for {
              _ <- trace.put(traceParams(device): _*)
              _ <- span("refresh.device") {
                device.refresh
              }
              key <- device.updatedKey
              _ <- trace.put("key" -> key)
            } yield key -> device
          }
        })
        .flatMap { devs =>
          val devMap = devs.toMap
          Stream
            .fromIterator[F](current.filterKeys(!devMap.keySet.contains(_)).values.iterator)
            .through(onUpdate)
            .compile
            .drain
            .as(devMap)
        }
    }

    Resource.liftF(Slf4jLogger.fromName[F](s"deviceState-$deviceType")).flatMap { implicit logger =>
      DataPoller.traced[F, G, Map[String, V], Unit]("device.state", "device.type" -> deviceType)(
        (current: Data[Map[String, V]]) => deviceState(current.value),
        pollInterval,
        errorCount,
        (_: Map[String, V], _: Map[String, V]) => Applicative[F].unit
      ) { (_, _) =>
        ()
      }
    }
  }
}