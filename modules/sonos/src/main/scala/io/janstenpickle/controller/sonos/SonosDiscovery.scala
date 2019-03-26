package io.janstenpickle.controller.sonos

import java.util.concurrent.TimeUnit

import cats.Eq
import cats.effect._
import cats.effect.concurrent.Ref
import cats.instances.list._
import cats.instances.map._
import cats.instances.tuple._
import cats.instances.long._
import cats.instances.string._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import com.vmichalak.sonoscontroller
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.catseffect.CatsEffect._
import io.janstenpickle.controller.poller.{DataPoller, Empty}
import io.janstenpickle.controller.poller.DataPoller.Data

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
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

  def polling[F[_]: ContextShift](
    config: Polling,
    commandTimeout: FiniteDuration,
    onUpdate: () => F[Unit],
    ec: ExecutionContext,
    onDeviceUpdate: () => F[Unit]
  )(implicit F: Concurrent[F], timer: Timer[F]): Resource[F, SonosDiscovery[F]] = {
    val timeout: Long = (config.discoveryInterval * 3).toMillis

    def suspendErrorsEval[A](thunk: => A): F[A] = suspendErrorsEvalOn(thunk, ec)

    def updateDevices(
      devicesRef: Ref[F, Map[String, SonosDevice[F]]],
      current: (Map[NonEmptyString, SonosDevice[F]], Map[NonEmptyString, Long])
    ): F[(Map[NonEmptyString, SonosDevice[F]], Map[NonEmptyString, Long])] =
      for {
        discovered <- suspendErrorsEval(sonoscontroller.SonosDiscovery.discover().asScala.toList)
        devices <- discovered.traverse { device =>
          for {
            id <- suspendErrorsEval(device.getSpeakerInfo.getLocalUID)
            name <- suspendErrorsEval(device.getZoneName)
            formattedName <- F.fromEither(NonEmptyString.from(snakify(name)).leftMap(new RuntimeException(_)))
            nonEmptyName <- F.fromEither(NonEmptyString.from(name).leftMap(new RuntimeException(_)))
          } yield (id, formattedName, nonEmptyName, device)
        }
        now <- timer.clock.realTime(TimeUnit.MILLISECONDS)
        (newDevices, expiryMap) = devices.foldLeft((List.empty[F[SonosDevice[F]]], current._2)) {
          case ((devs, expiries), (id, name, nonEmptyName, device)) =>
            val newDevs =
              if (devs.contains(name)) devs
              else
                SonosDevice[F](id, name, nonEmptyName, device, devicesRef, commandTimeout, ec, onDeviceUpdate) :: devs
            (newDevs, expiries.updated(name, now))

        }
        newDeviceMap <- newDevices.sequence
        newExp = expiryMap.filter { case (_, ts) => (now - ts) < timeout }
        deviceMap = current._1.filterKeys(newExp.contains) ++ newDeviceMap.map(d => d.name -> d).toMap
        _ <- devicesRef.set(deviceMap.values.map(d => d.id -> d).toMap)
      } yield (deviceMap, newExp)

    Resource.liftF(Ref.of(Map.empty[String, SonosDevice[F]])).flatMap { ref =>
      DataPoller[F, (Map[NonEmptyString, SonosDevice[F]], Map[NonEmptyString, Long]), SonosDiscovery[F]](
        (current: Data[(Map[NonEmptyString, SonosDevice[F]], Map[NonEmptyString, Long])]) =>
          updateDevices(ref, current.value),
        config.discoveryInterval,
        config.errorCount,
        (_: (Map[NonEmptyString, SonosDevice[F]], Map[NonEmptyString, Long])) => onUpdate()
      ) { (getData, _) =>
        new SonosDiscovery[F] {
          override def devices: F[Map[NonEmptyString, SonosDevice[F]]] = getData().map(_._1)
        }
      }.flatMap { disc =>
        SonosDeviceState[F](config.stateUpdateInterval, config.errorCount, disc, onDeviceUpdate).map(_ => disc)
      }
    }
  }
}
