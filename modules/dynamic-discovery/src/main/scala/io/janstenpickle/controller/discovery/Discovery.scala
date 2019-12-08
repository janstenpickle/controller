package io.janstenpickle.controller.discovery

import java.util.concurrent.TimeUnit

import cats.effect._
import cats.instances.long._
import cats.instances.map._
import cats.instances.set._
import cats.instances.tuple._
import cats.kernel.Semigroup
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._
import cats.{Eq, FlatMap, Parallel}
import eu.timepit.refined.types.numeric.PosInt
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.model.DiscoveredDeviceKey
import io.janstenpickle.controller.poller.{DataPoller, Empty}
import natchez.{Trace, TraceValue}

import scala.concurrent.duration._

trait Discovery[F[_], K, V] {
  def devices: F[Discovered[K, V]]
  def reinit: F[Unit]
}

object Discovery {
  case class Polling(
    discoveryInterval: FiniteDuration = 40.seconds,
    stateUpdateInterval: FiniteDuration = 10.seconds,
    errorCount: PosInt = PosInt(3)
  )

  def combined[F[_]: FlatMap, K, V](x: Discovery[F, K, V], y: Discovery[F, K, V]): Discovery[F, K, V] =
    new Discovery[F, K, V] {
      override def devices: F[Discovered[K, V]] = x.devices.flatMap(xs => y.devices.map(xs |+| _))
      override def reinit: F[Unit] = x.reinit >> y.reinit
    }

  implicit def discoverySemigroup[F[_]: FlatMap, K, V]: Semigroup[Discovery[F, K, V]] =
    new Semigroup[Discovery[F, K, V]] {
      override def combine(x: Discovery[F, K, V], y: Discovery[F, K, V]): Discovery[F, K, V] = combined(x, y)
    }

  def apply[F[_]: Parallel: ContextShift, G[_]: Timer: Concurrent, K: Eq, V: Eq](
    deviceType: String,
    config: Polling,
    onUpdate: ((Set[DiscoveredDeviceKey], Map[K, V], Map[K, Long])) => F[Unit],
    onDeviceUpdate: () => F[Unit],
    doDiscovery: () => F[(Set[DiscoveredDeviceKey], Map[K, V])],
    refresh: V => F[Unit],
    makeKey: V => F[String],
    traceParams: V => List[(String, TraceValue)] = (_: V) => List.empty,
  )(
    implicit F: Sync[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, Discovery[F, K, V]] = {
    lazy val timeout: Long = (config.discoveryInterval * 3).toMillis

    implicit val empty: Empty[(Set[DiscoveredDeviceKey], Map[K, V], Map[K, Long])] =
      new Empty[(Set[DiscoveredDeviceKey], Map[K, V], Map[K, Long])] {
        override def empty: (Set[DiscoveredDeviceKey], Map[K, V], Map[K, Long]) =
          (Set.empty, Map.empty, Map.empty)
      }

    def updateDevices(
      current: (Set[DiscoveredDeviceKey], Map[K, V], Map[K, Long])
    ): F[(Set[DiscoveredDeviceKey], Map[K, V], Map[K, Long])] =
      trace.span(s"${deviceType}UpdateDevices") {
        for {
          _ <- trace.put("current.count" -> current._2.size)
          (unmapped, discovered) <- trace.span("discover") {
            doDiscovery().flatTap {
              case (u, d) =>
                trace.put("discovered.count" -> d.size, "unmapped.count" -> u.size)
            }
          }
          now <- timer.clock.realTime(TimeUnit.MILLISECONDS)
          updatedExpiry = discovered
            .foldLeft(current._3) {
              case (exp, (k, _)) =>
                exp.updated(k, now)
            }
            .filter { case (_, ts) => (now - ts) < timeout }
          updatedCurrent = current._2.filterKeys(updatedExpiry.keySet.contains)
          devices = discovered ++ updatedCurrent
          _ <- trace.put("new.count" -> devices.size)
        } yield (unmapped, devices, updatedExpiry)
      }

    DataPoller
      .traced[F, G, (Set[DiscoveredDeviceKey], Map[K, V], Map[K, Long]), Discovery[F, K, V]](s"${deviceType}Discovery")(
        current => updateDevices(current.value),
        config.discoveryInterval,
        config.errorCount,
        onUpdate
      )(
        (getData, setData) =>
          new Discovery[F, K, V] {
            override def devices: F[Discovered[K, V]] = getData().map {
              case (unmapped, devices, _) => Discovered(unmapped, devices)
            }

            override def reinit: F[Unit] = updateDevices((Set.empty, Map.empty, Map.empty)).flatMap(setData)
        }
      )
      .flatMap { disc =>
        DeviceState[F, G, K, V](
          deviceType,
          config.stateUpdateInterval,
          config.errorCount,
          disc,
          onDeviceUpdate,
          refresh,
          makeKey,
          traceParams
        ).map(_ => disc)
      }

  }
}
