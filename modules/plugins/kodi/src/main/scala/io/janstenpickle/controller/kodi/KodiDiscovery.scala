package io.janstenpickle.controller.kodi

import java.net.{Inet4Address, InetAddress, NetworkInterface}

import cats.{MonadError, Parallel}
import cats.effect.{Blocker, Clock, Concurrent, ContextShift, Resource, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import javax.jmdns.{JmDNS, ServiceInfo}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.parallel._
import cats.instances.list._
import cats.instances.option._
import cats.syntax.traverse._
import cats.syntax.applicativeError._
import eu.timepit.refined.types.net.PortNumber
import org.http4s.client.Client
import cats.syntax.functor._
import cats.derived.auto.eq._
import eu.timepit.refined.cats._
import cats.instances.map._
import cats.instances.string._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import natchez.Trace
import cats.instances.int._
import cats.instances.tuple._
import cats.instances.long._
import io.janstenpickle.controller.configsource.{ConfigSource, WritableConfigSource}
import io.janstenpickle.controller.discovery.{DeviceRename, DeviceState, Discovered, Discovery, MetadataConstants}
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue, Room}
import org.http4s.circe.CirceEntityDecoder._
import MetadataConstants._
import fs2.{Pipe, Stream}
import io.circe.Json
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.kodi.config.KodiRemoteConfigSource
import io.janstenpickle.controller.model.event.{ConfigEvent, DeviceDiscoveryEvent, RemoteEvent, SwitchEvent}
import io.janstenpickle.controller.model.event.ConfigEvent.{RemoteAddedEvent, RemoteRemovedEvent}
import io.janstenpickle.controller.model.event.SwitchEvent.{
  SwitchAddedEvent,
  SwitchRemovedEvent,
  SwitchStateUpdateEvent
}
import org.http4s.Uri

import scala.collection.JavaConverters._

object KodiDiscovery {
  case class KodiInstance(name: NonEmptyString, room: NonEmptyString, host: NonEmptyString, port: PortNumber)

  final val eventSource = "kodi"

  private final val uuid = "uuid"

  private def onDeviceUpdate[F[_]: MonadError[*[_], Throwable]](
    config: KodiComponents.Config,
    configEventPublisher: EventPublisher[F, ConfigEvent]
  ): Pipe[F, KodiDevice[F], Unit] =
    _.evalMap { device =>
      KodiRemoteConfigSource.deviceToRemotes(config.remote, config.activityConfig.name, device)
    }.flatMap(Stream.emits).map(RemoteAddedEvent(_, eventSource))

  private def onDeviceAdded[F[_]: Concurrent: Clock: Trace](
    config: KodiComponents.Config,
    configEventPublisher: EventPublisher[F, ConfigEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    remoteEventPublisher: EventPublisher[F, RemoteEvent]
  ): Pipe[F, KodiDevice[F], Unit] = stream => {

    val addedSwitches: Pipe[F, KodiDevice[F], Unit] = _.flatMap { dev =>
      Stream.fromIterator[F](
        KodiSwitchProvider.deviceToSwitches(config.switchDevice, dev, switchEventPublisher.narrow).iterator
      )
    }.evalMap {
        case (key, switch) =>
          switch.getState.map { state =>
            List(SwitchAddedEvent(key, switch.metadata), SwitchStateUpdateEvent(key, state))
          }
      }
      .flatMap(Stream.emits)
      .through(switchEventPublisher.pipe)

    val addedRemotes: Pipe[F, KodiDevice[F], Unit] = _.evalMap(
      KodiRemoteConfigSource.deviceToRemotes(config.remote, config.activityConfig.name, _)
    ).flatMap(Stream.emits).map(r => RemoteAddedEvent(r, eventSource)).through(configEventPublisher.pipe)

    val learntCommands: Pipe[F, KodiDevice[F], Unit] = _.flatMap { device =>
      Stream.emits(KodiRemoteControl.commands[F].keys.toList.map { cmd =>
        RemoteEvent.RemoteLearntCommand(config.remote, device.name, KodiRemoteControl.CommandSource, cmd)
      })
    }.through(remoteEventPublisher.pipe)

    stream.broadcastThrough(addedSwitches, addedRemotes, learntCommands)
  }

  private def onDeviceRemoved[F[_]: Concurrent: Clock: Trace](
    config: KodiComponents.Config,
    configEventPublisher: EventPublisher[F, ConfigEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent]
  ): Pipe[F, KodiDevice[F], Unit] = stream => {

    val removedSwitches: Pipe[F, KodiDevice[F], Unit] = _.flatMap { dev =>
      Stream.fromIterator[F](
        KodiSwitchProvider.deviceToSwitches(config.switchDevice, dev, switchEventPublisher.narrow).keys.iterator
      )
    }.map(SwitchRemovedEvent).through(switchEventPublisher.pipe)

    val removedRemotes: Pipe[F, KodiDevice[F], Unit] = _.evalMap(
      KodiRemoteConfigSource.deviceToRemotes(config.remote, config.activityConfig.name, _)
    ).flatMap(Stream.emits).map(r => RemoteRemovedEvent(r.name, eventSource)).through(configEventPublisher.pipe)

    stream.broadcastThrough(removedSwitches, removedRemotes)
  }

  def static[F[_]: Parallel: Trace: KodiErrors: Clock, G[_]: Timer: Concurrent](
    client: Client[F],
    config: KodiComponents.Config,
    remoteEventPublisher: EventPublisher[F, RemoteEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    configEventPublisher: EventPublisher[F, ConfigEvent],
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent]
  )(implicit F: Concurrent[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, KodiDiscovery[F]] = {
    val discovery = Resource
      .liftF(
        config.instances
          .traverse { instance =>
            for {
              kodiClient <- KodiClient[F](client, instance.name, instance.host, instance.port)
              device <- KodiDevice[F](
                kodiClient,
                instance.name,
                instance.room,
                instance.host.value,
                config.switchDevice,
                DiscoveredDeviceKey(s"${instance.name}_${instance.host}", DeviceName),
                switchEventPublisher.narrow
              )
            } yield (instance.name, device)
          }
          .map { devs =>
            new Discovery[F, NonEmptyString, KodiDevice[F]] {
              override def devices: F[Discovered[NonEmptyString, KodiDevice[F]]] =
                F.pure(Discovered(Map.empty, devs.toMap))

              override def reinit: F[Unit] = F.unit
            }
          }
      )

    val deviceDiscovered: Pipe[F, KodiDevice[F], Unit] = _.map { dev =>
      DeviceDiscoveryEvent.DeviceDiscovered(dev.key, dev.value)
    }.through(discoveryEventPublisher.pipe)

    val deviceRemoved: Pipe[F, KodiDevice[F], Unit] = _.map { dev =>
      DeviceDiscoveryEvent.DeviceRemoved(dev.key)
    }.through(discoveryEventPublisher.pipe)

    for {
      disc <- discovery
      _ <- if (config.instances.nonEmpty)
        DeviceState[F, G, NonEmptyString, KodiDevice[F]](
          DeviceName,
          config.polling.stateUpdateInterval,
          config.polling.errorCount,
          disc,
          onDeviceUpdate(config, configEventPublisher),
        )
      else Resource.pure[F, Unit](())
      _ <- Resource.make(disc.devices.flatMap { discovered =>
        Stream
          .fromIterator[F](discovered.devices.values.iterator)
          .broadcastThrough(
            onDeviceAdded(config, configEventPublisher, switchEventPublisher, remoteEventPublisher),
            deviceDiscovered
          )
          .compile
          .drain *> remoteEventPublisher.publish1(
          RemoteEvent.RemoteAddedEvent(KodiRemoteControl.RemoteName, eventSource)
        )
      })(
        _ =>
          disc.devices.flatMap { discovered =>
            Stream
              .fromIterator[F](discovered.devices.values.iterator)
              .broadcastThrough(onDeviceRemoved(config, configEventPublisher, switchEventPublisher), deviceRemoved)
              .compile
              .drain *> remoteEventPublisher.publish1(
              RemoteEvent.RemoteRemovedEvent(KodiRemoteControl.RemoteName, eventSource)
            )
        }
      )
    } yield disc
  }

  def dynamic[F[_]: Parallel: ContextShift: KodiErrors, G[_]: Timer: Concurrent](
    client: Client[F],
    blocker: Blocker,
    config: KodiComponents.Config,
    nameMapping: ConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    remoteEventPublisher: EventPublisher[F, RemoteEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    configEventPublisher: EventPublisher[F, ConfigEvent],
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent]
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, KodiDiscovery[F]] = {
    def jmDNS: Resource[F, List[JmDNS]] =
      Resource
        .liftF(
          config.discoveryBindAddress.fold(
            blocker.delay[F, List[InetAddress]](
              NetworkInterface.getNetworkInterfaces.asScala
                .flatMap(_.getInetAddresses.asScala)
                .toList
                .filter(_.isInstanceOf[Inet4Address])
            )
          )(addr => F.pure(List(addr)))
        )
        .flatMap {
          case Nil =>
            Resource.make(blocker.delay[F, JmDNS](JmDNS.create()))(j => blocker.delay[F, Unit](j.close())).map(List(_))
          case addrs =>
            addrs.traverse[Resource[F, *], JmDNS] { addr =>
              Resource.make(blocker.delay[F, JmDNS](JmDNS.create(addr)))(j => blocker.delay[F, Unit](j.close()))
            }
        }

    def isKodiInstance(host: NonEmptyString, port: PortNumber): F[Boolean] =
      F.fromEither(Uri.fromString(s"http://${host.value}:${port.value}/jsonrpc")).flatMap { uri =>
        client.expect[Json](uri).map(_ => true).handleError(_ => false)
      }

    def serviceToAddress(service: ServiceInfo): Option[(NonEmptyString, PortNumber)] =
      for {
        address <- service.getInet4Addresses.headOption
        host <- NonEmptyString.from(address.getHostAddress).toOption
        port <- PortNumber.from(service.getPort).toOption
      } yield (host, port)

    def serviceToInstance(service: ServiceInfo): Option[KodiInstance] =
      for {
        maybeRoomName <- service.getName.split("\\.", 2).toList match {
          case name :: room :: Nil => Some((name, room))
          case _ => None
        }
        name <- NonEmptyString.from(maybeRoomName._1).toOption
        room <- NonEmptyString.from(maybeRoomName._2).toOption
        (host, port) <- serviceToAddress(service)
      } yield KodiInstance(name, room, host, port)

    def serviceDeviceKey(service: ServiceInfo): Option[DiscoveredDeviceKey] =
      Option(service.getPropertyString(uuid)).map { uuid =>
        DiscoveredDeviceKey(s"${service.getName}_$uuid", DeviceName)
      }

    def serviceMetadata(service: ServiceInfo): Map[String, String] =
      Map(Name -> service.getName) ++ service.getInet4Addresses.headOption
        .map(ip => Host -> ip.getHostAddress) ++ Option(service.getPropertyString(uuid)).map(uuid -> _)

    def serviceInstance(
      service: ServiceInfo
    ): F[Option[Either[(DiscoveredDeviceKey, Map[String, String]), KodiInstance]]] =
      (for {
        deviceId <- serviceDeviceKey(service)
        (host, port) <- serviceToAddress(service)
      } yield
        isKodiInstance(host, port)
          .ifM[Option[Either[(DiscoveredDeviceKey, Map[String, String]), KodiInstance]]](
            nameMapping.getValue(deviceId).map { maybeName =>
              Some(
                maybeName
                  .flatMap(dv => dv.room.map(dv.name -> _))
                  .map { case (name, room) => KodiInstance(name, room, host, port) }
                  .orElse(serviceToInstance(service))
                  .toRight((deviceId, serviceMetadata(service)))
              )

            },
            F.pure(None)
          )).flatSequence

    def serviceInstanceDevice(
      service: ServiceInfo
    ): F[Option[Either[(DiscoveredDeviceKey, Map[String, String]), (NonEmptyString, KodiDevice[F])]]] =
      serviceInstance(service).flatMap {
        case Some(Left(unmappedKey)) => F.pure(Some(Left(unmappedKey)))
        case Some(Right(instance)) =>
          serviceDeviceKey(service).traverse { deviceId =>
            for {
              kodiClient <- KodiClient[F](client, instance.name, instance.host, instance.port)
              device <- KodiDevice[F](
                kodiClient,
                instance.name,
                instance.room,
                instance.host.value,
                config.switchDevice,
                deviceId,
                switchEventPublisher.narrow
              )
            } yield Right((instance.name, device))
          }
        case None => F.pure(None)
      }

    def discover: F[(Map[DiscoveredDeviceKey, Map[String, String]], Map[NonEmptyString, KodiDevice[F]])] =
      trace.span("kodi.discover") {
        blocker
          .blockOn[F, List[ServiceInfo]](jmDNS.use { js =>
            trace.span("bonjour.list.devices") {
              F.delay(js.map(_.getInetAddress.toString).mkString(",")).flatMap { addrs =>
                trace.put("bind.addresses" -> addrs)
              } *> js.parFlatTraverse(j => F.delay(j.list("_http._tcp.local.").toList))
            }
          })
          .flatMap { services =>
            services
              .parTraverse(serviceInstanceDevice)
              .map(
                _.foldLeft(
                  (Map.empty[DiscoveredDeviceKey, Map[String, String]], Map.empty[NonEmptyString, KodiDevice[F]])
                ) {
                  case ((unmapped, devices), Some(Left(uk))) => (unmapped + uk, devices)
                  case ((unmapped, devices), Some(Right(dev))) => (unmapped, devices + dev)
                  case (acc, None) => acc
                }
              )
          }
          .flatTap {
            case (unmapped, devices) =>
              trace.put("unmapped.count" -> unmapped.size, "device.count" -> devices.size)
          }
      }

    Discovery[F, G, NonEmptyString, KodiDevice[F]](
      deviceType = DeviceName,
      config = config.polling,
      doDiscovery = discover,
      onDevicesUpdate = (_, _) => F.unit,
      onDeviceDiscovered = onDeviceAdded(config, configEventPublisher, switchEventPublisher, remoteEventPublisher),
      onDeviceRemoved = onDeviceRemoved(config, configEventPublisher, switchEventPublisher),
      onDeviceUpdate = onDeviceUpdate(config, configEventPublisher),
      discoveryEventProducer = discoveryEventPublisher,
      traceParams = device => List("device.name" -> device.name.value, "device.room" -> device.room.value)
    ).flatMap { disc =>
      Resource
        .make(remoteEventPublisher.publish1(RemoteEvent.RemoteAddedEvent(KodiRemoteControl.RemoteName, eventSource)))(
          _ => remoteEventPublisher.publish1(RemoteEvent.RemoteRemovedEvent(KodiRemoteControl.RemoteName, eventSource))
        )
        .map(_ => disc)
    }
  }
}
