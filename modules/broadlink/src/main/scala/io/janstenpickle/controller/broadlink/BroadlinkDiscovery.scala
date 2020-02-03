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
import fs2.{Pipe, Stream}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.broadlink.remote.{RmRemote, RmRemoteConfig}
import io.janstenpickle.controller.broadlink.switch.{SpSwitch, SpSwitchConfig}
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.discovery.MetadataConstants._
import io.janstenpickle.controller.discovery.{DeviceState, Discovered, Discovery}
import io.janstenpickle.controller.errors.ErrorHandler
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.{ConfigEvent, DeviceDiscoveryEvent, RemoteEvent, SwitchEvent}
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue, SwitchKey}
import io.janstenpickle.controller.store.SwitchStateStore
import natchez.Trace

import scala.collection.JavaConverters._
import scala.concurrent.duration._

object BroadlinkDiscovery {

  final val eventSource = "broadlink"

  case class Config(
    bindAddress: Option[InetAddress],
    remoteCommandTimeout: FiniteDuration = 200.millis,
    switchCommandTimeout: FiniteDuration = 3.seconds,
    discoverTimeout: FiniteDuration = 5.seconds,
    discoveryPort: PortNumber = PortNumber(9998),
    polling: Discovery.Polling,
  )

  def dynamic[F[_]: Parallel: ContextShift, G[_]: Timer: Concurrent](
    config: Config,
    workBlocker: Blocker,
    discoveryBlocker: Blocker,
    switchStateStore: SwitchStateStore[F],
    nameMapping: ConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    remoteEventPublisher: EventPublisher[F, RemoteEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    configEventPublisher: EventPublisher[F, ConfigEvent],
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent]
  )(
    implicit F: Concurrent[F],
    errorHandler: ErrorHandler[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, BroadlinkDiscovery[F]] = Resource.liftF(Slf4jLogger.create[F]).flatMap { logger =>
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

    def makeDevice(device: BLDevice, name: NonEmptyString): F[Option[((NonEmptyString, String), BroadlinkDevice[F])]] =
      device match {
        case dev: RM2Device =>
          F.pure(
            Some(
              (name, "remote") -> BroadlinkDevice
                .Remote(RmRemote.fromDevice[F](name, config.remoteCommandTimeout, dev, workBlocker))
            )
          )
        case dev: SP1Device =>
          F.pure(
            Some(
              (name, "switch") -> BroadlinkDevice
                .Switch(SpSwitch.makeSp1[F](name, config.switchCommandTimeout, dev, switchStateStore, workBlocker))
            )
          )
        case dev: SP2Device =>
          SpSwitch
            .makeSp23[F](name, config.switchCommandTimeout, dev, workBlocker, switchEventPublisher.narrow)
            .map(sp => Some((name, "switch") -> BroadlinkDevice.Switch(sp)))
        case _ => F.pure(None)
      }

    def metadata(device: BLDevice): Map[String, String] =
      Map(Host -> device.getHost, "mac" -> device.getMac.getMacString)

    def assignDevice(
      device: BLDevice
    ): F[Option[Either[(DiscoveredDeviceKey, Map[String, String]), ((NonEmptyString, String), BroadlinkDevice[F])]]] =
      devSwitch(device).flatTraverse { key =>
        nameMapping.getValue(key).flatMap {
          case None => F.pure(Some(Left(key -> metadata(device))))
          case Some(value) =>
            errorHandler
              .handleWith(makeDevice(device, value.name).handleError(_ => None))(
                logger.warn(_)(s"Failed to create broadlink device with ID '${key.deviceId}' on discovery").as(None)
              )
              .map(_.map(Either.right(_)))
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
      trace.span("broadlink.discover") {
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
          .flatTap {
            case (unmapped, devices) =>
              trace.put("unmapped.count" -> unmapped.size, "device.count" -> devices.size)
          }
      }

    val onRemoteDiscovered: Pipe[F, BroadlinkDevice[F], Unit] = _.map {
      case BroadlinkDevice.Remote(remote) =>
        Some(RemoteEvent.RemoteAddedEvent(remote.name, eventSource))
      case _ => None
    }.unNone.through(remoteEventPublisher.pipe)

    val onSwitchDiscovered: Pipe[F, BroadlinkDevice[F], Unit] = _.evalMap {
      case BroadlinkDevice.Switch(switch) =>
        switch.getState.map { state =>
          val key = SwitchKey(switch.device, switch.name)
          List[SwitchEvent](
            SwitchEvent.SwitchAddedEvent(key, switch.metadata),
            SwitchEvent.SwitchStateUpdateEvent(key, state)
          )
        }
      case _ => F.pure(List.empty[SwitchEvent])
    }.flatMap(Stream.emits).through(switchEventPublisher.pipe)

    val onDeviceDiscovered: Pipe[F, BroadlinkDevice[F], Unit] =
      _.broadcastThrough(onRemoteDiscovered, onSwitchDiscovered)

    val onRemoteRemoved: Pipe[F, BroadlinkDevice[F], Unit] = _.map {
      case BroadlinkDevice.Remote(remote) =>
        Some(RemoteEvent.RemoteRemovedEvent(remote.name, eventSource))
      case _ => None
    }.unNone.through(remoteEventPublisher.pipe)

    val onSwitchRemoved: Pipe[F, BroadlinkDevice[F], Unit] = _.map {
      case BroadlinkDevice.Switch(switch) =>
        Some(SwitchEvent.SwitchRemovedEvent(SwitchKey(switch.device, switch.name)))
      case _ => None
    }.unNone.through(switchEventPublisher.pipe)

    val onDeviceRemoved: Pipe[F, BroadlinkDevice[F], Unit] =
      _.broadcastThrough(onRemoteRemoved, onSwitchRemoved)

    Discovery[F, G, (NonEmptyString, String), BroadlinkDevice[F]](
      deviceType = DeviceName,
      config = config.polling,
      doDiscovery = discover,
      onDevicesUpdate = (_, _) => F.unit,
      onDeviceDiscovered = onDeviceDiscovered,
      onDeviceRemoved = onDeviceRemoved,
      onDeviceUpdate = _.map(_ => ()),
      discoveryEventProducer = discoveryEventPublisher,
      traceParams = {
        case BroadlinkDevice.Switch(device) =>
          List(
            "device.name" -> device.name.value,
            "device.type" -> device.device.value,
            "device.host" -> device.host,
            "device.mac" -> device.mac
          )
        case BroadlinkDevice.Remote(device) =>
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
