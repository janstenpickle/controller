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
import io.janstenpickle.controller.discovery.{DeviceState, Discovery}

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
              device <- KodiDevice[F](kodiClient, instance.name, instance.room, onDeviceUpdate)
            } yield (instance.name, device)
          }
          .map { devs =>
            new Discovery[F, NonEmptyString, KodiDevice[F]] {
              override lazy val devices: F[Map[NonEmptyString, KodiDevice[F]]] = F.pure(devs.toMap)
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

  def dynamic[F[_]: Parallel: ContextShift: KodiErrors, G[_]: Timer: Concurrent](
    client: Client[F],
    blocker: Blocker,
    bindAddress: Option[InetAddress],
    config: Discovery.Polling,
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

    def discover: F[Map[KodiInstance, KodiDevice[F]]] =
      blocker
        .blockOn[F, List[ServiceInfo]](jmDNS.use { js =>
          trace.span("bonjourListDevices") {
            F.delay(js.map(_.getInetAddress.toString).mkString(",")).flatMap { addrs =>
              trace.put("bind.addresses" -> addrs)
            } *> js.parFlatTraverse(j => F.delay(j.list("_http._tcp.local.").toList))
          }
        })
        .flatMap { services =>
          val instances = services.flatMap { service =>
            for {
              maybeRoomName <- service.getName.split("\\.", 2).toList match {
                case name :: room :: Nil => Some((name, room))
                case _ => None
              }
              name <- NonEmptyString.from(maybeRoomName._1).toOption
              room <- NonEmptyString.from(maybeRoomName._2).toOption
              address <- service.getInet4Addresses.headOption
              host <- NonEmptyString.from(address.getHostAddress).toOption
              port <- PortNumber.from(service.getPort).toOption
            } yield KodiInstance(name, room, host, port)
          }

          instances
            .traverse { instance =>
              for {
                kodiClient <- KodiClient[F](client, instance.name, instance.host, instance.port)
                device <- KodiDevice[F](kodiClient, instance.name, instance.room, onDeviceUpdate)
              } yield (instance, device)
            }
            .map(_.toMap)
        }

    Discovery[F, G, KodiInstance, NonEmptyString, KodiDevice[F]](
      deviceName,
      config,
      _ => onUpdate(),
      onDeviceUpdate,
      () => discover,
      _.name,
      _.refresh,
      deviceKey,
      device => List("device.name" -> device.name.value, "device.room" -> device.room.value)
    )
  }
}
