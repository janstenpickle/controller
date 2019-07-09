package io.janstenpickle.controller.sonos

import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.list._
import cats.instances.set._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{~>, Parallel}
import eu.timepit.refined.types.numeric.PosInt
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data
import natchez.Trace

import scala.concurrent.duration.FiniteDuration

object SonosDeviceState {
  def apply[F[_]: Sync: Parallel, G[_]: Concurrent: Timer](
    pollInterval: FiniteDuration,
    errorCount: PosInt,
    discovery: SonosDiscovery[F],
    onUpdate: () => F[Unit]
  )(implicit trace: Trace[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, Unit] = {
    def deviceState: F[Set[String]] = trace.span("sonosDeviceState") {
      trace
        .span("readDevices") {
          discovery.devices
        }
        .flatMap(_.values.toList.parTraverse { device =>
          trace.span("readDevice") {
            for {
              _ <- trace.put("device.name" -> device.name.value, "device.id" -> device.id)
              _ <- trace.span("refreshDevice") {
                device.refresh
              }
              state <- device.getState
            } yield s"${device.name}${device.label}${state.isPlaying}${state.nowPlaying}"
          }
        })
        .map(_.toSet)
    }

    DataPoller.traced[F, G, Set[String], Unit]("sonosDeviceState")(
      (_: Data[Set[String]]) => deviceState,
      pollInterval,
      errorCount,
      (_: Set[String]) => onUpdate()
    ) { (_, _) =>
      ()
    }
  }
}
