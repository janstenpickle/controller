package io.janstenpickle.controller.broadlink

import java.net.InetAddress

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import com.github.mob41.blapi.{BLDevice, RM2Device, SP1Device, SP2Device}
import eu.timepit.refined.types.net.PortNumber
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.discovery.{DeviceRename, Discovery}
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import natchez.Trace
import cats.syntax.apply._
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.traverse._
import cats.instances.option._
import cats.instances.list._
import cats.instances.either._
import cats.instances.string._
import cats.syntax.either._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.broadlink.remote.RmRemote
import io.janstenpickle.controller.broadlink.switch.SpSwitch
import io.janstenpickle.controller.remote.Remote
import io.janstenpickle.controller.store.SwitchStateStore

import scala.concurrent.duration._

object BroadlinkDiscovery {
  private final val deviceName = "broadlink"

  case class Config(
    bindAddress: Option[InetAddress],
    rmCommandTimeoutMillis: PosInt = PosInt(100),
    discoverTimeout: FiniteDuration = 5.seconds,
    discoveryPort: Option[PortNumber],
    polling: Discovery.Polling,
  )

  private def devType(dev: String): String = s"$deviceName-$dev"

  def dynamic[F[_]: Parallel: ContextShift, G[_]: Timer: Concurrent](
    config: Config,
    blocker: Blocker,
    switchStateStore: SwitchStateStore[F],
    nameMapping: WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    onUpdate: () => F[Unit],
    onDeviceUpdate: () => F[Unit]
  )(
    implicit F: Sync[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, (DeviceRename[F], BroadlinkDiscovery[F])] = {

    type X = Either[SpSwitch[F], RmRemote[F]]

    def deviceKey(device: X): F[String] = device match {
      case Right(remote) => F.pure(remote.name.value)
      case Left(switch) =>
        switch.getState.map { state =>
          s"${switch.name}_${switch.device}_${state.value}"
        }
    }

    def devSwitch(device: BLDevice) = device match {
      case _: RM2Device => Some(DiscoveredDeviceKey(device.getMac.getMacString, devType("remote")))
      case _: SP1Device => Some(DiscoveredDeviceKey(device.getMac.getMacString, devType("switch")))
      case _: SP2Device => Some(DiscoveredDeviceKey(device.getMac.getMacString, devType("switch")))
      case _ => None
    }

    def devDerp(device: BLDevice, name: NonEmptyString): F[Option[(NonEmptyString, X)]] =
      device match {
        case dev: RM2Device =>
          F.pure(Some(name -> Right(RmRemote.fromDevice[F](name, config.rmCommandTimeoutMillis, dev, blocker))))
        case dev: SP1Device => F.pure(Some(name -> Left(SpSwitch.makeSp1[F](name, dev, switchStateStore, blocker))))
        case dev: SP2Device => SpSwitch.makeSp23[F](name, dev, blocker).map(sp => Some(name -> Left(sp)))
        case _ => F.pure(None)
      }

    def shit(device: BLDevice): F[Option[Either[DiscoveredDeviceKey, (NonEmptyString, X)]]] =
      devSwitch(device).flatTraverse { key =>
        nameMapping.getValue(key).flatMap {
          case None => F.pure(Some(Left(key)))
          case Some(value) =>
            devDerp(device, value.name)
              .map(_.map(Either.right(_)))
        }
      }

    def discover: F[(Set[DiscoveredDeviceKey], Map[NonEmptyString, X])] =
      for {
        devices <- blocker.delay[F, Array[BLDevice]](
          BLDevice.discoverDevices(
            InetAddress.getLocalHost,
            config.discoveryPort.fold(0)(_.value),
            config.discoverTimeout.toMillis.toInt
          )
        )
        x <- devices.toList
          .flatTraverse(shit(_).map(_.toList))
          .map(_.foldLeft((Set.empty[DiscoveredDeviceKey], Map.empty[NonEmptyString, X])) {
            case ((unmapped, discovered), Left(key)) => (unmapped + key, discovered)
            case ((unmapped, discovered), Right(device)) => (unmapped, discovered + device)
          })
      } yield x

    Discovery[F, G, NonEmptyString, X](
      deviceName,
      config.polling,
      _ => onUpdate(),
      onDeviceUpdate,
      () => discover, {
        case Left(sp) => sp.refresh
        case Right(_) => F.unit
      },
      deviceKey,
      device => List.empty //List("device.name" -> device.name.value, "device.room" -> device.room.value)
    ).map { disc =>
      new DeviceRename[F] {
        override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Unit] =
          if (k.deviceType == devType("remote") || k.deviceType == devType("switch"))
            nameMapping.upsert(k, v) *> disc.reinit
          else F.unit

        override def unassigned: F[Set[DiscoveredDeviceKey]] = disc.devices.map(_.unmapped)

        override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] =
          disc.devices.map(_.devices.map {
            case (_, Right(v)) => DiscoveredDeviceKey(v.mac, devType("remote")) -> DiscoveredDeviceValue(v.name, None)
            case (_, Left(v)) => DiscoveredDeviceKey(v.mac, devType("switch")) -> DiscoveredDeviceValue(v.name, None)
          })
      } -> disc
    }
  }
}
