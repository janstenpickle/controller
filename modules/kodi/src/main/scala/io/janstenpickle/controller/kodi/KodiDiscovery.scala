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
import io.circe.Json
import org.http4s.Uri

import scala.collection.JavaConverters._

object KodiDiscovery {
  case class KodiInstance(name: NonEmptyString, room: NonEmptyString, host: NonEmptyString, port: PortNumber)

  private final val uuid = "uuid"

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
                DiscoveredDeviceKey(s"${instance.name}_${instance.host}", DeviceName),
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
        if (kodis.nonEmpty)
          DeviceState[F, G, NonEmptyString, KodiDevice[F]](
            DeviceName,
            config.stateUpdateInterval,
            config.errorCount,
            disc,
            onDeviceUpdate,
            _.refresh,
            deviceKey
          ).map(_ => disc)
        else Resource.pure(disc)
      }

  def dynamic[F[_]: Parallel: ContextShift: KodiErrors, G[_]: Timer: Concurrent](
    client: Client[F],
    blocker: Blocker,
    bindAddress: Option[InetAddress],
    config: Discovery.Polling,
    nameMapping: ConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    onUpdate: () => F[Unit],
    onDeviceUpdate: () => F[Unit]
  )(
    implicit F: Sync[F],
    timer: Timer[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, KodiDiscovery[F]] = {
    def jmDNS: Resource[F, List[JmDNS]] =
      Resource
        .liftF(
          bindAddress.fold(
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
              device <- KodiDevice[F](kodiClient, instance.name, instance.room, deviceId, onDeviceUpdate)
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
      DeviceName,
      config,
      _ => onUpdate(),
      onDeviceUpdate,
      () => discover,
      _.refresh,
      deviceKey,
      device => List("device.name" -> device.name.value, "device.room" -> device.room.value)
    )
  }
}
