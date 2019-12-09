package io.janstenpickle.controller.kodi

import java.net.{Inet4Address, InetAddress, NetworkInterface}

import cats.{Functor, Parallel}
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import javax.jmdns.{JmDNS, ServiceInfo}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.parallel._
import cats.instances.list._
import cats.syntax.traverse._
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
import MetadataConstants._

import scala.collection.JavaConverters._

object KodiDiscovery {
  private final val deviceName = "kodi"

  case class KodiInstance(name: NonEmptyString, room: NonEmptyString, host: NonEmptyString, port: PortNumber)

  private def deviceKey[F[_]: Functor](device: KodiDevice[F]): F[String] = device.getState.map { state =>
    s"${device.name}${device.room}${state.isPlaying}"
  }

  def static[F[_]: Parallel: Trace: KodiErrors, G[_]: Timer: Concurrent](
    client: Client[F],
    kodis: List[KodiInstance],
    config: Discovery.Polling,
    onDeviceUpdate: () => F[Unit]
  )(implicit F: Sync[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, KodiDiscovery[F]] =
    Resource
      .liftF(
        kodis
          .traverse { instance =>
            for {
              kodiClient <- KodiClient[F](client, instance.name, instance.host, instance.port)
              device <- KodiDevice[F](
                kodiClient,
                instance.name,
                instance.room,
                DiscoveredDeviceKey(s"${instance.name}_${instance.host}", deviceName),
                onDeviceUpdate
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
      .flatMap { disc =>
        DeviceState[F, G, NonEmptyString, KodiDevice[F]](
          deviceName,
          config.stateUpdateInterval,
          config.errorCount,
          disc,
          onDeviceUpdate,
          _.refresh,
          deviceKey
        ).map(_ => disc)
      }

  // FIXME kodi causes blocking behaviour
  def dynamic[F[_]: Parallel: ContextShift: KodiErrors, G[_]: Timer: Concurrent](
    client: Client[F],
    blocker: Blocker,
    bindAddress: Option[InetAddress],
    config: Discovery.Polling,
    nameMapping: WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    onUpdate: () => F[Unit],
    onDeviceUpdate: () => F[Unit]
  )(
    implicit F: Sync[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, (DeviceRename[F], KodiDiscovery[F])] = {
    def jmDNS: Resource[F, List[JmDNS]] =
      Resource
        .liftF(
          bindAddress.fold(
            F.delay(
              NetworkInterface.getNetworkInterfaces.asScala
                .flatMap(_.getInetAddresses.asScala)
                .toList
                .filter(_.isInstanceOf[Inet4Address])
            )
          )(addr => F.delay(List(addr)))
        )
        .flatMap {
          case Nil => Resource.make(F.delay(JmDNS.create()))(j => F.delay(j.close())).map(List(_))
          case addrs =>
            addrs.traverse[Resource[F, *], JmDNS] { addr =>
              Resource.make(F.delay(JmDNS.create(addr)))(j => F.delay(j.close()))
            }
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

    def serviceDeviceKey(service: ServiceInfo): DiscoveredDeviceKey =
      DiscoveredDeviceKey(
        s"${service.getName}_${service.getInet4Addresses.headOption.fold("")(_.getHostAddress)}",
        deviceName
      )

    def serviceMetadata(service: ServiceInfo): Map[String, String] =
      Map(Host -> service.getInet4Addresses.headOption.fold("")(_.getHostAddress), Name -> service.getName)

    def serviceInstance(service: ServiceInfo): F[Either[(DiscoveredDeviceKey, Map[String, String]), KodiInstance]] = {
      val deviceId = serviceDeviceKey(service)
      nameMapping.getValue(deviceId).map { maybeName =>
        (for {
          (name, room) <- maybeName.flatMap(dv => dv.room.map(dv.name -> _))
          (host, port) <- serviceToAddress(service)
        } yield KodiInstance(name, room, host, port))
          .orElse(serviceToInstance(service))
          .toRight((deviceId, serviceMetadata(service)))
      }
    }

    def serviceInstanceDevice(
      service: ServiceInfo
    ): F[Either[(DiscoveredDeviceKey, Map[String, String]), (NonEmptyString, KodiDevice[F])]] =
      serviceInstance(service).flatMap {
        case Left(unmappedKey) => F.pure(Left(unmappedKey))
        case Right(instance) =>
          for {
            kodiClient <- KodiClient[F](client, instance.name, instance.host, instance.port)
            device <- KodiDevice[F](kodiClient, instance.name, instance.room, serviceDeviceKey(service), onDeviceUpdate)
          } yield Right((instance.name, device))
      }

    def discover: F[(Map[DiscoveredDeviceKey, Map[String, String]], Map[NonEmptyString, KodiDevice[F]])] =
      blocker
        .blockOn[F, List[ServiceInfo]](jmDNS.use { js =>
          trace.span("bonjourListDevices") {
            F.delay(js.map(_.getInetAddress.toString).mkString(",")).flatMap { addrs =>
              trace.put("bind.addresses" -> addrs)
            } *> js.parFlatTraverse(j => F.delay(j.list("_http._tcp.local.").toList))
          }
        })
        .flatMap { services =>
          services
            .traverse(serviceInstanceDevice)
            .map(
              _.foldLeft(
                (Map.empty[DiscoveredDeviceKey, Map[String, String]], Map.empty[NonEmptyString, KodiDevice[F]])
              ) {
                case ((unmapped, devices), Left(uk)) => (unmapped + uk, devices)
                case ((unmapped, devices), Right(dev)) => (unmapped, devices + dev)
              }
            )
        }

    Discovery[F, G, NonEmptyString, KodiDevice[F]](
      deviceName,
      config,
      _ => onUpdate(),
      onDeviceUpdate,
      () => discover,
      _.refresh,
      deviceKey,
      device => List("device.name" -> device.name.value, "device.room" -> device.room.value)
    ).map { disc =>
      new DeviceRename[F] {
        override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]] =
          if (k.deviceType == deviceName) (nameMapping.upsert(k, v) *> disc.reinit).map(Some(_))
          else F.pure(None)

        override def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]] = disc.devices.map(_.unmapped)

        override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] =
          disc.devices.map(_.devices.map { case (_, v) => v.key -> DiscoveredDeviceValue(v.name, Some(v.room)) })
      } -> disc
    }
  }
}
