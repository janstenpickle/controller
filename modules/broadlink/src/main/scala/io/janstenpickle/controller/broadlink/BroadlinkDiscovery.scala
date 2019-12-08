package io.janstenpickle.controller.broadlink

import java.net.{Inet4Address, InetAddress, NetworkInterface}

import cats.{Applicative, Parallel}
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import com.github.mob41.blapi.{BLDevice, RM2Device, SP1Device, SP2Device}
import eu.timepit.refined.types.net.PortNumber
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.discovery.{DeviceRename, DeviceState, Discovered, Discovery}
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
import cats.instances.tuple._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.parallel._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.broadlink.remote.{RmRemote, RmRemoteConfig}
import io.janstenpickle.controller.broadlink.switch.{SpSwitch, SpSwitchConfig}
import io.janstenpickle.controller.store.SwitchStateStore

import scala.concurrent.duration._
import scala.collection.JavaConverters._

object BroadlinkDiscovery {
  private final val deviceName = "broadlink"

  type BroadlinkDevice[F[_]] = Either[SpSwitch[F], RmRemote[F]]

  case class Config(
    bindAddress: Option[InetAddress],
    commandTimeout: FiniteDuration = 100.millis,
    discoverTimeout: FiniteDuration = 5.seconds,
    discoveryPort: Option[PortNumber],
    polling: Discovery.Polling,
  )

  private def devType(dev: String): String = s"$deviceName-$dev"

  private def formatDeviceId(id: String): String = id.replace(":", "")

  def deviceKey[F[_]](device: BroadlinkDevice[F])(implicit F: Applicative[F]): F[String] = device match {
    case Right(remote) => F.pure(remote.name.value)
    case Left(switch) =>
      switch.getState.map { state =>
        s"${switch.name}_${switch.device}_${state.value}"
      }
  }

  def static[F[_]: ContextShift: Timer: Parallel: Trace, G[_]: Timer: Concurrent](
    rm: List[RmRemoteConfig],
    sp: List[SpSwitchConfig],
    switchStateStore: SwitchStateStore[F],
    blocker: Blocker,
    config: Discovery.Polling,
    onDeviceUpdate: () => F[Unit]
  )(implicit F: Concurrent[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, BroadlinkDiscovery[F]] = {
    type Dev = BroadlinkDevice[F]

    Resource
      .liftF(
        Parallel.parMap2(
          rm.parTraverse[F, ((NonEmptyString, String), Dev)] { config =>
            RmRemote[F](config, blocker).map { dev =>
              (dev.name, "remote") -> Right(dev)
            }
          },
          sp.parTraverse[F, ((NonEmptyString, String), Dev)] { config =>
            SpSwitch[F](config, switchStateStore, blocker).map { dev =>
              (dev.name, "switch") -> Left(dev)
            }
          }
        ) { (rms, sps) =>
          lazy val devs = (rms ++ sps).toMap
          new Discovery[F, (NonEmptyString, String), Dev] {
            override def devices: F[Discovered[(NonEmptyString, String), Dev]] =
              F.pure(Discovered(Set.empty, devs))

            override def reinit: F[Unit] = F.unit
          }

        }
      )
      .flatMap { disc =>
        DeviceState[F, G, (NonEmptyString, String), Dev](
          deviceName,
          config.stateUpdateInterval,
          config.errorCount,
          disc,
          onDeviceUpdate, {
            case Left(sp) => sp.refresh
            case Right(_) => F.unit
          },
          deviceKey
        ).map(_ => disc)
      }
  }

  def dynamic[F[_]: Parallel: ContextShift, G[_]: Timer: Concurrent](
    config: Config,
    workBlocker: Blocker,
    discoveryBlocker: Blocker,
    switchStateStore: SwitchStateStore[F],
    nameMapping: WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    onUpdate: () => F[Unit],
    onDeviceUpdate: () => F[Unit]
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, (DeviceRename[F], BroadlinkDiscovery[F])] = {
    type Dev = BroadlinkDevice[F]

    def devSwitch(device: BLDevice) = {
      lazy val name = formatDeviceId(device.getMac.getMacString)
      device match {
        case _: RM2Device =>
          Some(DiscoveredDeviceKey(name, devType("remote")))
        case _: SP1Device =>
          Some(DiscoveredDeviceKey(name, devType("switch")))
        case _: SP2Device =>
          Some(DiscoveredDeviceKey(name, devType("switch")))
        case _ => None
      }
    }

    def makeDevice(device: BLDevice, name: NonEmptyString): F[Option[((NonEmptyString, String), Dev)]] =
      device match {
        case dev: RM2Device =>
          F.pure(Some((name, "remote") -> Right(RmRemote.fromDevice[F](name, config.commandTimeout, dev, workBlocker))))
        case dev: SP1Device =>
          F.pure(
            Some(
              (name, "switch") -> Left(
                SpSwitch.makeSp1[F](name, config.commandTimeout, dev, switchStateStore, workBlocker)
              )
            )
          )
        case dev: SP2Device =>
          SpSwitch
            .makeSp23[F](name, config.commandTimeout, dev, workBlocker)
            .map(sp => Some((name, "switch") -> Left(sp)))
        case _ => F.pure(None)
      }

    def assignDevice(device: BLDevice): F[Option[Either[DiscoveredDeviceKey, ((NonEmptyString, String), Dev)]]] =
      devSwitch(device).flatTraverse { key =>
        nameMapping.getValue(key).flatMap {
          case None => F.pure(Some(Left(key)))
          case Some(value) =>
            makeDevice(device, value.name)
              .map(_.map(Either.right(_)))
        }
      }

    def runDiscovery: F[List[BLDevice]] =
      config.bindAddress
        .fold(
          F.delay(
            NetworkInterface.getNetworkInterfaces.asScala
              .flatMap(_.getInetAddresses.asScala)
              .toList
              .filter(_.isInstanceOf[Inet4Address])
          )
        )(addr => F.delay(List(addr)))
        .flatMap(_.parFlatTraverse { addr =>
          discoveryBlocker
            .delay[F, List[BLDevice]](
              BLDevice
                .discoverDevices(addr, config.discoveryPort.fold(0)(_.value), config.discoverTimeout.toMillis.toInt)
                .toList
            )
            .handleError(_ => List.empty[BLDevice])
        })

    def discover: F[(Set[DiscoveredDeviceKey], Map[(NonEmptyString, String), Dev])] =
      runDiscovery.flatMap(
        _.parFlatTraverse(assignDevice(_).map(_.toList))
          .map(_.foldLeft((Set.empty[DiscoveredDeviceKey], Map.empty[(NonEmptyString, String), Dev])) {
            case ((unmapped, discovered), Left(key)) => (unmapped + key, discovered)
            case ((unmapped, discovered), Right(device)) => (unmapped, discovered + device)
          })
      )

    Discovery[F, G, (NonEmptyString, String), Dev](
      deviceName,
      config.polling,
      _ => onUpdate(),
      onDeviceUpdate,
      () => discover, {
        case Left(sp) => sp.refresh
        case Right(_) => F.unit
      },
      deviceKey, {
        case Left(device) =>
          List(
            "device.name" -> device.name.value,
            "device.type" -> device.device.value,
            "device.host" -> device.host,
            "device.mac" -> device.mac
          )
        case Right(device) =>
          List(
            "device.name" -> device.name.value,
            "device.type" -> "rm",
            "device.host" -> device.host,
            "device.mac" -> device.mac
          )
      }
    ).map { disc =>
      new DeviceRename[F] {
        override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]] =
          if (k.deviceType == devType("remote") || k.deviceType == devType("switch"))
            (nameMapping.upsert(k, v) *> disc.reinit).map(Some(_))
          else F.pure(None)

        override def unassigned: F[Set[DiscoveredDeviceKey]] = disc.devices.map(_.unmapped)

        override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] =
          disc.devices.map(_.devices.map {
            case (_, Right(v)) =>
              DiscoveredDeviceKey(formatDeviceId(v.mac), devType("remote")) -> DiscoveredDeviceValue(v.name, None)
            case (_, Left(v)) =>
              DiscoveredDeviceKey(formatDeviceId(v.mac), devType("switch")) -> DiscoveredDeviceValue(v.name, None)
          })
      } -> disc
    }
  }
}
