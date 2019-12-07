package io.janstenpickle.controller.tplink

import java.io.ByteArrayInputStream
import java.net.{DatagramPacket, DatagramSocket, InetAddress, SocketTimeoutException}

import cats.derived.auto.eq._
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.instances.list._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.syntax.traverse._
import cats.{Functor, Parallel}
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import io.circe.parser._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.discovery.{DeviceRename, DeviceState, Discovered, Discovery}
import io.janstenpickle.controller.tplink.Constants._
import io.janstenpickle.controller.tplink.device.{TplinkDevice, TplinkDeviceErrors}
import natchez.Trace
import cats.derived.auto.eq._
import eu.timepit.refined.cats._
import cats.instances.string._
import cats.instances.int._
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue, Room}

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object TplinkDiscovery {
  private final val name = "tplink"

  private final val discoveryQuery =
    s"""{
      $InfoCommand,
      "emeter": {"get_realtime": null},
      "smartlife.iot.dimmer": {"get_dimmer_parameters": null},
      "smartlife.iot.common.emeter": {"get_realtime": null},
      "smartlife.iot.smartbulb.lightingservice": {"get_light_state": null}
     }"""

  private final val broadcastAddress = "255.255.255.255"

  case class TplinkInstance(
    name: NonEmptyString,
    room: Option[NonEmptyString],
    host: NonEmptyString,
    port: PortNumber,
    `type`: DeviceType
  )

  private def deviceKey[F[_]: Functor](device: TplinkDevice[F]): F[String] = device.getState.map { state =>
    s"${device.name}${state.isOn}"
  }

  def static[F[_]: ContextShift: Timer: Parallel: Trace: TplinkDeviceErrors, G[_]: Timer: Concurrent](
    tplinks: List[TplinkInstance],
    commandTimeout: FiniteDuration,
    blocker: Blocker,
    config: Discovery.Polling,
    onDeviceUpdate: () => F[Unit]
  )(implicit F: Concurrent[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, TplinkDiscovery[F]] =
    Resource
      .liftF(
        tplinks
          .parFlatTraverse[F, ((NonEmptyString, DeviceType), TplinkDevice[F])] {
            case TplinkInstance(name, room, host, port, t @ DeviceType.SmartPlug(model)) =>
              TplinkDevice
                .plug[F](
                  TplinkClient[F](name, room, host, port, commandTimeout, blocker),
                  model,
                  Json.Null,
                  onDeviceUpdate
                )
                .map { dev =>
                  List(((name, t), dev))
                }
            case _ => F.pure(List.empty[((NonEmptyString, DeviceType), TplinkDevice[F])])
          }
          .map { devs =>
            new Discovery[F, (NonEmptyString, DeviceType), TplinkDevice[F]] {
              override def devices: F[Discovered[(NonEmptyString, DeviceType), TplinkDevice[F]]] =
                F.pure(Discovered(Set.empty, devs.toMap))

              override def reinit: F[Unit] = F.unit
            }
          }
      )
      .flatMap { disc =>
        DeviceState[F, G, (NonEmptyString, DeviceType), TplinkDevice[F]](
          name,
          config.stateUpdateInterval,
          config.errorCount,
          disc,
          onDeviceUpdate,
          _.refresh,
          deviceKey
        ).map(_ => disc)
      }

  def dynamic[F[_]: Parallel: ContextShift: TplinkDeviceErrors: Trace, G[_]: Timer: Concurrent](
    port: PortNumber,
    commandTimeout: FiniteDuration,
    discoveryTimeout: FiniteDuration,
    blocker: Blocker,
    config: Discovery.Polling,
    onUpdate: () => F[Unit],
    onDeviceUpdate: () => F[Unit]
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    errors: TplinkErrors[F],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, (DeviceRename[F], TplinkDiscovery[F])] = {
    def refineF[A](refined: Either[String, A]): F[A] =
      F.fromEither(refined.leftMap(new RuntimeException(_) with NoStackTrace))

    def jsonInfo(json: Json): Option[Json] =
      json.hcursor.downField(Constants.System).downField(Constants.GetSysInfo).focus

    def deviceType(json: Json): Option[DeviceType] =
      for {
        sysInfo <- jsonInfo(json)
        model <- deviceModel(sysInfo)
        cursor = sysInfo.hcursor
        typeString <- cursor.get[String]("type").orElse(cursor.get[String]("mic_type")).toOption
        deviceType <- typeString.toLowerCase match {
          case dt if dt.contains("smartplug") && sysInfo.asObject.fold(false)(_.toMap.contains("children")) =>
            Some(DeviceType.SmartStrip(model))
          case dt if dt.contains("smartplug") => Some(DeviceType.SmartPlug(model))
          case dt if dt.contains("smartbulb") => Some(DeviceType.SmartBulb(model))
          case _ => None
        }
      } yield deviceType

    def deviceName(json: Json): Option[String] =
      for {
        sysInfo <- jsonInfo(json)
        alias <- sysInfo.hcursor.get[String]("alias").toOption
      } yield alias

    def deviceNameRoom(str: String): Option[(NonEmptyString, Option[Room])] = str.split('|').toList match {
      case n :: r :: Nil =>
        for {
          name <- NonEmptyString.from(n).toOption
          room <- NonEmptyString.from(r).toOption
        } yield (name, Some(room))
      case n :: Nil => NonEmptyString.from(n).toOption.map(_ -> None)
      case _ => None
    }

    def deviceModel(json: Json): Option[NonEmptyString] =
      for {
        str <- json.hcursor.get[String]("model").toOption
        m <- str.split('(').headOption
        model <- NonEmptyString.from(m).toOption
      } yield model

    def socketResource: Resource[F, DatagramSocket] =
      Resource.make {
        F.delay {
          val socket = new DatagramSocket()
          socket.setBroadcast(true)
          socket.setSoTimeout(discoveryTimeout.toMillis.toInt)
          socket
        }
      } { s =>
        F.delay(s.close())
      }

    def discover: F[(Set[DiscoveredDeviceKey], Map[(NonEmptyString, DeviceType), TplinkDevice[F]])] =
      socketResource.use { socket =>
        val (_, buffer) = Encryption.encryptWithHeader(discoveryQuery).splitAt(4)
        val packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(broadcastAddress), port.value)

        def receiveData: F[Map[(NonEmptyString, PortNumber), Json]] =
          F.tailRecM(Map.empty[(NonEmptyString, PortNumber), Json]) { state =>
            val inBuffer: Array[Byte] = Array.fill(4096)(1.byteValue())
            val inPacket = new DatagramPacket(inBuffer, inBuffer.length)

            val ret: F[Either[Map[(NonEmptyString, PortNumber), Json], Map[(NonEmptyString, PortNumber), Json]]] = for {
              _ <- F.delay(socket.receive(inPacket))
              in <- Encryption.decrypt[F](new ByteArrayInputStream(inBuffer), 1)
              host <- refineF(NonEmptyString.from(inPacket.getAddress.getHostAddress))
              port <- refineF(PortNumber.from(inPacket.getPort))
              json <- parse(in).fold(errors.decodingFailure(host, _), _.pure[F])
            } yield Left(state + (((host, port), json)))

            ret.recover {
              case _: SocketTimeoutException => Right(state)
            }
          }

        for {
          _ <- blocker.blockOn(List.fill(3)(F.delay(socket.send(packet)) *> timer.sleep(50.millis)).sequence)
          discovered <- blocker.blockOn(receiveData)
          filtered = discovered.flatMap {
            case ((host, port), v) =>
              for {
                dt <- deviceType(v)
                str <- deviceName(v)
                (name, room) <- deviceNameRoom(str)
              } yield (TplinkInstance(name, room, host, port, dt), v)
          }.toList
          devices <- filtered.parFlatTraverse[F, ((NonEmptyString, DeviceType), TplinkDevice[F])] {
            case (TplinkInstance(name, room, host, port, t @ DeviceType.SmartPlug(model)), json) =>
              TplinkDevice
                .plug(TplinkClient(name, room, host, port, commandTimeout, blocker), model, json, onDeviceUpdate)
                .map { dev =>
                  List(((name, t), dev))
                }
            case (TplinkInstance(name, room, host, port, t @ DeviceType.SmartBulb(model)), json) =>
              TplinkDevice
                .bulb(TplinkClient(name, room, host, port, commandTimeout, blocker), model, json, onDeviceUpdate)
                .map { dev =>
                  List(((name, t), dev))
                }
            case _ => F.pure(List.empty[((NonEmptyString, DeviceType), TplinkDevice[F])])
          }
        } yield (Set.empty[DiscoveredDeviceKey], devices.toMap)
      }

    Discovery[F, G, (NonEmptyString, DeviceType), TplinkDevice[F]](
      name,
      config,
      _ => onUpdate(),
      onDeviceUpdate,
      () => discover,
      _.refresh,
      deviceKey,
      device => List("device.name" -> device.name.value)
    ).map { disc =>
      new DeviceRename[F] {
        override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Unit] =
          disc.devices.flatMap(
            _.devices
              .collectFirst {
                case ((name, t), dev) if name.value == k.deviceId && t.model.value == k.deviceType => dev
              }
              .fold(F.unit)(_.rename(v.name, v.room) *> disc.reinit)
          )

        override def unassigned: F[Set[DiscoveredDeviceKey]] = disc.devices.map(_.unmapped)

        override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] =
          disc.devices.map(_.devices.map {
            case ((name, t), dev) =>
              DiscoveredDeviceKey(name.value, s"$name-${t.model.value}") -> DiscoveredDeviceValue(dev.name, dev.room)
          })
      } -> disc
    }
  }
}
