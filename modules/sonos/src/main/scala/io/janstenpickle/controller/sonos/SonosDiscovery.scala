package io.janstenpickle.controller.sonos

import cats.effect._
import cats.effect.concurrent.Ref
import cats.instances.list._
import cats.instances.long._
import cats.instances.map._
import cats.instances.string._
import cats.instances.tuple._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{~>, Eq, Parallel}
import com.vmichalak.sonoscontroller
import com.vmichalak.sonoscontroller.{SonosDevice => JSonosDevice}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.discovery.Discovery
import io.janstenpickle.controller.poller.Empty
import natchez.Trace

import scala.collection.JavaConverters._
import scala.concurrent.duration._

object SonosDiscovery {
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
    config: Discovery.Polling,
    commandTimeout: FiniteDuration,
    onUpdate: () => F[Unit],
    blocker: Blocker,
    onDeviceUpdate: () => F[Unit]
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, SonosDiscovery[F]] =
    Resource.liftF(Ref.of[F, Map[String, SonosDevice[F]]](Map.empty)).flatMap { devicesRef =>
      def discover: F[Map[NonEmptyString, SonosDevice[F]]] =
        blocker
          .delay[F, List[JSonosDevice]](sonoscontroller.SonosDiscovery.discover().asScala.toList)
          .flatMap { devices =>
            trace.put("device.count" -> devices.size).as(devices)
          }
          .flatMap { discovered =>
            discovered
              .parTraverse { device =>
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
                    dev <- SonosDevice[F](
                      id,
                      formattedName,
                      nonEmptyName,
                      device,
                      devicesRef,
                      commandTimeout,
                      blocker,
                      onDeviceUpdate
                    )
                  } yield dev.name -> dev

                }
              }
              .map(_.toMap)
          }

      Discovery[F, G, NonEmptyString, NonEmptyString, SonosDevice[F]](
        "sonos",
        config,
        data => devicesRef.set(data._1.values.map(d => d.id -> d).toMap) *> onUpdate(),
        onDeviceUpdate,
        () => discover,
        identity,
        _.refresh,
        device =>
          device.getState.map { state =>
            s"${device.name}${device.label}${state.isPlaying}${state.nowPlaying}"
        },
        device => List("device.name" -> device.name.value, "device.id" -> device.id),
      )
    }
}
