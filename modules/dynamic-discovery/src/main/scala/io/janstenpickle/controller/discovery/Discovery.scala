package io.janstenpickle.controller.discovery

import java.util.concurrent.TimeUnit

import cats.effect._
import cats.instances.long._
import cats.instances.map._
import cats.instances.tuple._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Eq, FlatMap, Parallel}
import eu.timepit.refined.types.numeric.PosInt
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.poller.{DataPoller, Empty}
import natchez.{Trace, TraceValue}

import scala.concurrent.duration._

trait Discovery[F[_], K, V] {
  def devices: F[Map[K, V]]
}

object Discovery {
  case class Polling(
    discoveryInterval: FiniteDuration = 40.seconds,
    stateUpdateInterval: FiniteDuration = 10.seconds,
    errorCount: PosInt = PosInt(3)
  )

  def combined[F[_]: FlatMap, K, V](x: Discovery[F, K, V], y: Discovery[F, K, V]): Discovery[F, K, V] =
    new Discovery[F, K, V] {
      override def devices: F[Map[K, V]] = x.devices.flatMap(xs => y.devices.map(xs ++ _))
    }

  def apply[F[_]: Parallel: ContextShift, G[_]: Timer: Concurrent, K0: Eq, K1, V: Eq](
    deviceType: String,
    config: Polling,
    onUpdate: ((Map[K0, V], Map[K0, Long])) => F[Unit],
    onDeviceUpdate: () => F[Unit],
    doDiscovery: () => F[Map[K0, V]],
    mapKey: K0 => K1,
    refresh: V => F[Unit],
    makeKey: V => F[String],
    traceParams: V => List[(String, TraceValue)] = (_: V) => List.empty,
  )(
    implicit F: Sync[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, Discovery[F, K1, V]] = {
    lazy val timeout: Long = (config.discoveryInterval * 3).toMillis

    implicit val empty: Empty[(Map[K0, V], Map[K0, Long])] =
      new Empty[(Map[K0, V], Map[K0, Long])] {
        override def empty: (Map[K0, V], Map[K0, Long]) =
          (Map.empty, Map.empty)
      }

    def updateDevices(current: (Map[K0, V], Map[K0, Long])): F[(Map[K0, V], Map[K0, Long])] =
      trace.span(s"${deviceType}UpdateDevices") {
        for {
          _ <- trace.put("current.count" -> current._1.size)
          discovered <- trace.span("discover") {
            doDiscovery().flatTap { d =>
              trace.put("discovered.count" -> d.size)
            }
          }
          now <- timer.clock.realTime(TimeUnit.MILLISECONDS)
          updatedExpiry = discovered
            .foldLeft(current._2) {
              case (exp, (k, _)) =>
                exp.updated(k, now)
            }
            .filter { case (_, ts) => (now - ts) < timeout }
          updatedCurrent = current._1.filterKeys(updatedExpiry.keySet.contains)
          devices = discovered ++ updatedCurrent
          _ <- trace.put("new.count" -> devices.size)
        } yield (devices, updatedExpiry)
      }

    DataPoller
      .traced[F, G, (Map[K0, V], Map[K0, Long]), Discovery[F, K1, V]]("kodiDiscovery")(
        current => updateDevices(current.value),
        config.discoveryInterval,
        config.errorCount,
        onUpdate
      )(
        (getData, _) =>
          new Discovery[F, K1, V] {
            override def devices: F[Map[K1, V]] = getData().map(_._1.map { case (k, v) => mapKey(k) -> v })
        }
      )
      .flatMap { disc =>
        DeviceState[F, G, K1, V](
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
