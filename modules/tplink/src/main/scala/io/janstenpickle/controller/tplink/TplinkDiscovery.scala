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
import io.janstenpickle.controller.discovery.{DeviceState, Discovery}
import io.janstenpickle.controller.tplink.Constants._
import io.janstenpickle.controller.tplink.hs100.{HS100Errors, HS100SmartPlug}
import natchez.Trace
import cats.derived.auto.eq._
import eu.timepit.refined.cats._
import cats.instances.string._
import cats.instances.int._
import io.janstenpickle.controller.model.DiscoveredDeviceKey

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

  case class TplinkInstance(name: NonEmptyString, host: NonEmptyString, port: PortNumber, `type`: DeviceType)

  private def deviceKey[F[_]: Functor](device: HS100SmartPlug[F]): F[String] = device.getState.map { state =>
    s"${device.name}${state.isOn}"
  }

  def static[F[_]: ContextShift: Timer: Parallel: Trace: HS100Errors, G[_]: Timer: Concurrent](
    tplinks: List[TplinkInstance],
    commandTimeout: FiniteDuration,
    blocker: Blocker,
    config: Discovery.Polling,
    onDeviceUpdate: () => F[Unit]
  )(implicit F: Concurrent[F], liftLower: ContextualLiftLower[G, F, String]): Resource[F, TplinkDiscovery[F]] =
    Resource
      .liftF(
        tplinks
          .parFlatTraverse[F, ((NonEmptyString, DeviceType), HS100SmartPlug[F])] {
            case TplinkInstance(name, host, port, DeviceType.SmartPlug) =>
              HS100SmartPlug[F](TplinkDevice[F](name, host, port, commandTimeout, blocker)).map { dev =>
                List(((name, DeviceType.SmartPlug), dev))
              }
            case _ => F.pure(List.empty[((NonEmptyString, DeviceType), HS100SmartPlug[F])])
          }
          .map { devs =>
            new Discovery[F, (NonEmptyString, DeviceType), HS100SmartPlug[F]] {
              override lazy val devices: F[Map[(NonEmptyString, DeviceType), HS100SmartPlug[F]]] = F.pure(devs.toMap)
            }
          }
      )
      .flatMap { disc =>
        DeviceState[F, G, (NonEmptyString, DeviceType), HS100SmartPlug[F]](
          name,
          config.stateUpdateInterval,
          config.errorCount,
          disc,
          onDeviceUpdate,
          _.refresh,
          deviceKey
        ).map(_ => disc)
      }

  def dynamic[F[_]: Parallel: ContextShift: HS100Errors: Trace, G[_]: Timer: Concurrent](
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
  ): Resource[F, TplinkDiscovery[F]] = {
    def refineF[A](refined: Either[String, A]): F[A] =
      F.fromEither(refined.leftMap(new RuntimeException(_) with NoStackTrace))

    def jsonInfo(json: Json): Option[Json] =
      json.hcursor.downField(Constants.System).downField(Constants.GetSysInfo).focus

    def deviceType(json: Json): Option[DeviceType] =
      for {
        sysInfo <- jsonInfo(json)
        cursor = sysInfo.hcursor
        typeString <- cursor.get[String]("type").orElse((cursor.get[String]("mic_type"))).toOption
        deviceType <- typeString.toLowerCase match {
          case dt if dt.contains("smartplug") && sysInfo.asObject.fold(false)(_.toMap.contains("children")) =>
            Some(DeviceType.SmartStrip)
          case dt if dt.contains("smartplug") => Some(DeviceType.SmartPlug)
          case dt if dt.contains("smartbulb") => Some(DeviceType.SmartBulb)
          case _ => None
        }
      } yield deviceType

    def deviceName(json: Json): Option[NonEmptyString] =
      for {
        sysInfo <- jsonInfo(json)
        alias <- sysInfo.hcursor.get[String]("alias").toOption
        name <- NonEmptyString.from(alias).toOption
      } yield name

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

    def discover = socketResource.use { socket =>
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
        _ <- List.fill(3)(F.delay(socket.send(packet)) *> timer.sleep(50.millis)).sequence
        discovered <- blocker.blockOn(receiveData)
        filtered = discovered.flatMap {
          case ((host, port), v) =>
            for {
              dt <- deviceType(v)
              name <- deviceName(v)
            } yield TplinkInstance(name, host, port, dt)
        }.toList
        devices <- filtered.parFlatTraverse {
          case k @ TplinkInstance(name, host, port, DeviceType.SmartPlug) =>
            HS100SmartPlug(TplinkDevice(name, host, port, commandTimeout, blocker)).map { dev =>
              List(k -> dev)
            }
          case _ => F.pure(List.empty[(TplinkInstance, HS100SmartPlug[F])])
        }
      } yield (Set.empty[DiscoveredDeviceKey], devices.toMap)
    }

    Discovery[F, G, TplinkInstance, (NonEmptyString, DeviceType), HS100SmartPlug[F]](
      name,
      config,
      _ => onUpdate(),
      onDeviceUpdate,
      () => discover,
      instance => (instance.name, instance.`type`),
      _.refresh,
      deviceKey,
      device => List("device.name" -> device.name.value)
    )
  }
}
