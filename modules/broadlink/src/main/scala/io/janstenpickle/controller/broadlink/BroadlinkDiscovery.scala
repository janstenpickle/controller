package io.janstenpickle.controller.broadlink

import java.net.{Inet4Address, InetAddress, NetworkInterface}

import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.instances.either._
import cats.instances.list._
import cats.instances.option._
import cats.instances.string._
import cats.instances.tuple._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.syntax.traverse._
import cats.{Applicative, Parallel}
import com.github.mob41.blapi.{BLDevice, RM2Device, SP1Device, SP2Device}
import eu.timepit.refined.cats._
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.broadlink.remote.{RmRemote, RmRemoteConfig}
import io.janstenpickle.controller.broadlink.switch.{SpSwitch, SpSwitchConfig}
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.discovery.MetadataConstants._
import io.janstenpickle.controller.discovery.{DeviceState, Discovered, Discovery}
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import io.janstenpickle.controller.store.SwitchStateStore
import natchez.Trace

import scala.collection.JavaConverters._
import scala.concurrent.duration._

object BroadlinkDiscovery {
  type BroadlinkDevice[F[_]] = Either[SpSwitch[F], RmRemote[F]]

  case class Config(
    bindAddress: Option[InetAddress],
    commandTimeout: FiniteDuration = 200.millis,
    discoverTimeout: FiniteDuration = 5.seconds,
    discoveryPort: PortNumber = PortNumber(9998),
    polling: Discovery.Polling,
  )

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
              F.pure(Discovered(Map.empty, devs))

            override def reinit: F[Unit] = F.unit
          }

        }
      )
      .flatMap { disc =>
        DeviceState[F, G, (NonEmptyString, String), Dev](
          DeviceName,
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
    nameMapping: ConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    onUpdate: () => F[Unit],
    onDeviceUpdate: () => F[Unit]
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, BroadlinkDiscovery[F]] = {
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

    def metadata(device: BLDevice): Map[String, String] =
      Map(Host -> device.getHost, "mac" -> device.getMac.getMacString)

    def assignDevice(
      device: BLDevice
    ): F[Option[Either[(DiscoveredDeviceKey, Map[String, String]), ((NonEmptyString, String), Dev)]]] =
      devSwitch(device).flatTraverse { key =>
        nameMapping.getValue(key).flatMap {
          case None => F.pure(Some(Left(key -> metadata(device))))
          case Some(value) => makeDevice(device, value.name).handleError(_ => None).map(_.map(Either.right(_)))
        }
      }

    def runDiscovery: F[List[BLDevice]] =
      config.bindAddress
        .fold(
          discoveryBlocker.delay[F, List[InetAddress]](
            NetworkInterface.getNetworkInterfaces.asScala
              .flatMap(_.getInetAddresses.asScala)
              .toList
              .filter(_.isInstanceOf[Inet4Address])
          )
        )(addr => F.pure(List(addr)))
        .flatMap(_.parFlatTraverse { addr =>
          discoveryBlocker
            .delay[F, List[BLDevice]](
              BLDevice
                .discoverDevices(addr, config.discoveryPort.value, config.discoverTimeout.toMillis.toInt)
                .toList
            )
            .handleError(_ => List.empty[BLDevice])
        })

    def discover: F[(Map[DiscoveredDeviceKey, Map[String, String]], Map[(NonEmptyString, String), Dev])] =
      runDiscovery
        .flatMap(
          _.parTraverse(assignDevice)
            .map(
              _.foldLeft(
                (Map.empty[DiscoveredDeviceKey, Map[String, String]], Map.empty[(NonEmptyString, String), Dev])
              ) {
                case ((unmapped, discovered), Some(Left(key))) => (unmapped + key, discovered)
                case ((unmapped, discovered), Some(Right(device))) => (unmapped, discovered + device)
                case (acc, None) => acc
              }
            )
        )

    Discovery[F, G, (NonEmptyString, String), Dev](
      DeviceName,
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
    )
  }
}
