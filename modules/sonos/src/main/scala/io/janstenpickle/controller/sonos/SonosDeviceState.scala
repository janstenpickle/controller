package io.janstenpickle.controller.sonos

import cats.effect.{Concurrent, Resource, Timer}
import cats.instances.list._
import cats.instances.set._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import eu.timepit.refined.types.numeric.PosInt
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data

import scala.concurrent.duration.FiniteDuration

object SonosDeviceState {
  def apply[F[_]: Concurrent: Timer](
    pollInterval: FiniteDuration,
    errorCount: PosInt,
    discovery: SonosDiscovery[F],
    onUpdate: () => F[Unit]
  ): Resource[F, Unit] = {
    def deviceState: F[Set[String]] =
      discovery.devices
        .flatMap(_.values.toList.traverse { device =>
          for {
            isPlaying <- device.isPlaying
            nowPlaying <- device.nowPlaying
          } yield s"${device.name}${device.label}$isPlaying$nowPlaying"
        })
        .map(_.toSet)

    DataPoller[F, Set[String], Unit](
      (_: Data[Set[String]]) => deviceState,
      pollInterval,
      errorCount,
      (_: Set[String]) => onUpdate()
    ) { (_, _) =>
      ()
    }
  }
}
