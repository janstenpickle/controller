package io.janstenpickle.controller.sonos

import java.util.concurrent.TimeUnit

import cats.effect._
import cats.effect.concurrent.Ref
import cats.instances.list._
import cats.instances.long._
import cats.instances.map._
import cats.instances.string._
import cats.instances.tuple._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{~>, Eq, Parallel}
import com.vmichalak.sonoscontroller
import com.vmichalak.sonoscontroller.{SonosDevice => JSonosDevice}
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.poller.{DataPoller, Empty}
import natchez.Trace

import scala.collection.JavaConverters._
import scala.concurrent.duration._

trait SonosDiscovery[F[_]] {
  def devices: F[Map[NonEmptyString, SonosDevice[F]]]
}

object SonosDiscovery {
  case class Polling(
    discoveryInterval: FiniteDuration = 40.seconds,
    stateUpdateInterval: FiniteDuration = 10.seconds,
    errorCount: PosInt = PosInt(5)
  )

  def snakify(name: String): String =
    name
      .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
      .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
      .replaceAll("\\s+", "_")
      .toLowerCase

  implicit def sonosDeviceEq[F[_]]: Eq[SonosDevice[F]] = Eq.by(_.name)
  implicit val nesEq: Eq[NonEmptyString] = Eq.by(_.value)

  implicit def empty[F[_]]: Empty[(Map[NonEmptyString, SonosDevice[F]], Map[NonEmptyString, Long])] =
    Empty((Map.empty[NonEmptyString, SonosDevice[F]], Map.empty[NonEmptyString, Long]))

  def polling[F[_]: ContextShift: Parallel, G[_]: Concurrent: Timer](
    config: Polling,
    commandTimeout: FiniteDuration,
    onUpdate: () => F[Unit],
    blocker: Blocker,
    onDeviceUpdate: () => F[Unit]
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, SonosDiscovery[F]] = {
    val timeout: Long = (config.discoveryInterval * 3).toMillis

    def updateDevices(
      devicesRef: Ref[F, Map[String, SonosDevice[F]]],
      current: (Map[NonEmptyString, SonosDevice[F]], Map[NonEmptyString, Long])
    ): F[(Map[NonEmptyString, SonosDevice[F]], Map[NonEmptyString, Long])] = trace.span("sonosUpdateDevices") {
      for {
        discovered <- trace.span("discover") {
          blocker.delay[F, List[JSonosDevice]](sonoscontroller.SonosDiscovery.discover().asScala.toList).flatMap {
            devices =>
              trace.put("device.count" -> devices.size).as(devices)
          }
        }
        devices <- discovered.parTraverse { device =>
          trace.span("readDevice") {
            for {
              id <- trace.span("getId") {
                blocker.delay[F, String](device.getSpeakerInfo.getLocalUID)
              }
              name <- trace.span("getZoneName") {
                blocker.delay[F, String](device.getZoneName)
              }
              formattedName <- F.fromEither(NonEmptyString.from(snakify(name)).leftMap(new RuntimeException(_)))
              nonEmptyName <- F.fromEither(NonEmptyString.from(name).leftMap(new RuntimeException(_)))
              _ <- trace.put("device.id" -> id, "device.name" -> name)
            } yield (id, formattedName, nonEmptyName, device)
          }
        }
        now <- timer.clock.realTime(TimeUnit.MILLISECONDS)
        (newDevices, expiryMap) = devices.foldLeft((List.empty[F[SonosDevice[F]]], current._2)) {
          case ((devs, expiries), (id, name, nonEmptyName, device)) =>
            val newDevs =
              if (devs.contains(name)) devs
              else
                SonosDevice[F](id, name, nonEmptyName, device, devicesRef, commandTimeout, blocker, onDeviceUpdate) :: devs
            (newDevs, expiries.updated(name, now))

        }
        newDeviceMap <- newDevices.parSequence
        newExp = expiryMap.filter { case (_, ts) => (now - ts) < timeout }
        deviceMap = current._1.filterKeys(newExp.contains) ++ newDeviceMap.map(d => d.name -> d).toMap
        _ <- devicesRef.set(deviceMap.values.map(d => d.id -> d).toMap)
      } yield (deviceMap, newExp)
    }

    Resource.liftF(Ref.of[F, Map[String, SonosDevice[F]]](Map.empty[String, SonosDevice[F]])).flatMap { ref =>
      DataPoller
        .traced[F, G, (Map[NonEmptyString, SonosDevice[F]], Map[NonEmptyString, Long]), SonosDiscovery[F]](
          "sonosDiscovery"
        )(
          (current: Data[(Map[NonEmptyString, SonosDevice[F]], Map[NonEmptyString, Long])]) =>
            updateDevices(ref, current.value),
          config.discoveryInterval,
          config.errorCount,
          (_: (Map[NonEmptyString, SonosDevice[F]], Map[NonEmptyString, Long])) => onUpdate()
        ) { (getData, _) =>
          new SonosDiscovery[F] {
            override def devices: F[Map[NonEmptyString, SonosDevice[F]]] = trace.span("sonosDiscoveryDevices") {
              getData().map(_._1)
            }
          }
        }
        .flatMap { disc =>
          SonosDeviceState[F, G](config.stateUpdateInterval, config.errorCount, disc, onDeviceUpdate)
            .map(_ => disc)
        }
    }
  }
}
