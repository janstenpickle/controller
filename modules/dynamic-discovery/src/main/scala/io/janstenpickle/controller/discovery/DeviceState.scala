package io.janstenpickle.controller.discovery

import cats.Parallel
import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.list._
import cats.instances.set._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import eu.timepit.refined.types.numeric.PosInt
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data
import natchez.{Trace, TraceValue}

import scala.concurrent.duration.FiniteDuration

object DeviceState {
  def apply[F[_]: Sync: Parallel, G[_]: Concurrent: Timer, K, V](
    deviceType: String,
    pollInterval: FiniteDuration,
    errorCount: PosInt,
    discovery: Discovery[F, K, V],
    onUpdate: () => F[Unit],
    refresh: V => F[Unit],
    makeKey: V => F[String],
    traceParams: V => List[(String, TraceValue)] = (_: V) => List.empty
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, Unit] = {
    def span[A](name: String)(fa: F[A]): F[A] = trace.span(name)(trace.put("device.type" -> deviceType) *> fa)

    def deviceState: F[Set[String]] = trace.span(s"deviceState") {
      span("readDevices") {
        discovery.devices
      }.flatMap(_.devices.values.toList.parTraverse { device =>
          span("readDevice") {
            for {
              _ <- trace.put(traceParams(device): _*)
              _ <- span("refreshDevice") {
                refresh(device)
              }
              key <- makeKey(device)
            } yield key
          }
        })
        .map(_.toSet)
    }

    DataPoller.traced[F, G, Set[String], Unit](s"deviceState", "device.type" -> deviceType)(
      (_: Data[Set[String]]) => deviceState,
      pollInterval,
      errorCount,
      (_: Set[String]) => onUpdate()
    ) { (_, _) =>
      ()
    }
  }
}
