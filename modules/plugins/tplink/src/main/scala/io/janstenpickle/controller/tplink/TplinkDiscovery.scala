package io.janstenpickle.controller.tplink

import java.io.ByteArrayInputStream
import java.net.{DatagramPacket, DatagramSocket, InetAddress, SocketTimeoutException}
import cats.derived.auto.eq._
import cats.effect.{Blocker, Clock, Concurrent, ContextShift, Resource, Timer}
import cats.effect.syntax.concurrent._
import cats.instances.list._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.syntax.traverse._
import cats.{Applicative, Functor, Parallel}
import eu.timepit.refined.types.net.PortNumber
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Json
import io.circe.parser._
import io.janstenpickle.controller.discovery.{DeviceRename, DeviceState, Discovered, Discovery}
import io.janstenpickle.controller.tplink.Constants._
import io.janstenpickle.controller.tplink.device.{TplinkDevice, TplinkDeviceErrors}
import cats.derived.auto.eq._
import eu.timepit.refined.cats._
import cats.instances.string._
import cats.instances.int._
import cats.instances.option._
import fs2.{Pipe, Stream}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.errors.ErrorHandler
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.{ConfigEvent, DeviceDiscoveryEvent, RemoteEvent, SwitchEvent}
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue, Room, SwitchKey}
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.tplink.config.TplinkRemoteConfigSource
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object TplinkDiscovery {
  private final val discoveryQuery =
    s"""{
      $InfoCommand,
      "emeter": {"get_realtime": null},
      "smartlife.iot.dimmer": {"get_dimmer_parameters": null},
      "smartlife.iot.common.emeter": {"get_realtime": null},
      "smartlife.iot.smartbulb.lightingservice": {"get_light_state": null}
     }"""

  private final val broadcastAddress = "255.255.255.255"

  final val eventSource = "tplink"

  case class TplinkInstance(
    name: NonEmptyString,
    room: Option[NonEmptyString],
    host: NonEmptyString,
    port: PortNumber,
    id: String,
    `type`: DeviceType
  )

  def dynamic[F[_]: Parallel: ContextShift, G[_]: Timer: Concurrent](
    config: TplinkComponents.Config,
    workBlocker: Blocker,
    discoveryBlocker: Blocker,
    remoteEventPublisher: EventPublisher[F, RemoteEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    configEventPublisher: EventPublisher[F, ConfigEvent],
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(
    implicit F: Concurrent[F],
    timer: Timer[F],
    errors: TplinkDeviceErrors[F] with ErrorHandler[F],
    trace: Trace[F],
    provide: Provide[G, F, Span[G]]
  ): Resource[F, TplinkDiscovery[F]] = Resource.liftF(Slf4jLogger.create[F]).flatMap { logger =>
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

    def deviceId(json: Json): Option[String] =
      for {
        sysInfo <- jsonInfo(json)
        id <- sysInfo.hcursor.get[String]("deviceId").toOption
      } yield id

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
            val socket = new DatagramSocket(config.discoveryPort.value)
            socket.setBroadcast(true)
            socket.setSoTimeout(config.discoveryTimeout.toMillis.toInt)
            socket
          }
          .timeout(config.discoveryTimeout)
      } { s =>
        F.delay(s.close())
      }

    def discover: F[
      (Map[DiscoveredDeviceKey, Map[String, String]], Map[(NonEmptyString, DeviceType), TplinkDevice[F]])
    ] =
      socketResource.use { socket =>
        val (_, buffer) = Encryption.encryptWithHeader(discoveryQuery).splitAt(4)
        val packet =
          new DatagramPacket(buffer, buffer.length, InetAddress.getByName(broadcastAddress), config.discoveryPort.value)

        def receiveData: F[Map[(NonEmptyString, PortNumber), Json]] =
          F.tailRecM(Map.empty[(NonEmptyString, PortNumber), Json]) { state =>
            val inBuffer: Array[Byte] = Array.fill(4096)(1.byteValue())
            val inPacket = new DatagramPacket(inBuffer, inBuffer.length)

            val ret: F[Either[Map[(NonEmptyString, PortNumber), Json], Map[(NonEmptyString, PortNumber), Json]]] = for {
              _ <- F.delay(socket.receive(inPacket))
              in <- Encryption.decrypt[F](new ByteArrayInputStream(inBuffer))
              host <- refineF(NonEmptyString.from(inPacket.getAddress.getHostAddress))
              port <- refineF(PortNumber.from(inPacket.getPort))
              json <- parse(in).fold(errors.decodingFailure(host, _), _.pure[F])
            } yield Left(state + (((host, port), json)))

            ret.recover {
              case _: SocketTimeoutException => Right(state)
            }
          }

        trace.span("tplink.discover") {
          for {
            _ <- discoveryBlocker.blockOn(List.fill(3)(F.delay(socket.send(packet)) *> timer.sleep(50.millis)).sequence)
            discovered <- discoveryBlocker.blockOn(receiveData)
            filtered = discovered.flatMap {
              case ((host, port), v) =>
                for {
                  dt <- deviceType(v)
                  str <- deviceName(v)
                  (name, room) <- deviceNameRoom(str)
                  id <- deviceId(v)
                } yield (TplinkInstance(name, room, host, port, id, dt), v)
            }.toList
            devices <- filtered.parFlatTraverse[F, ((NonEmptyString, DeviceType), TplinkDevice[F])] {
              case (TplinkInstance(name, room, host, port, id, t @ DeviceType.SmartPlug(model)), json) =>
                errors.handleWith(
                  TplinkDevice
                    .plug(
                      TplinkClient(name, room, host, port, config.commandTimeout, workBlocker),
                      model,
                      id,
                      json,
                      switchEventPublisher.narrow
                    )
                    .map { dev =>
                      List[((NonEmptyString, DeviceType), TplinkDevice[F])](((name, t), dev))
                    }
                )(
                  logger
                    .warn(_)(s"Failed to initialise TPLink Plug '$name''")
                    .as(List.empty[((NonEmptyString, DeviceType), TplinkDevice[F])])
                )
              case (TplinkInstance(name, room, host, port, id, t @ DeviceType.SmartBulb(model)), json) =>
                errors.handleWith(
                  TplinkDevice
                    .bulb(
                      TplinkClient(name, room, host, port, config.commandTimeout, workBlocker),
                      model,
                      id,
                      json,
                      switchEventPublisher.narrow
                    )
                    .map { dev =>
                      List[((NonEmptyString, DeviceType), TplinkDevice[F])](((name, t), dev))
                    }
                )(
                  logger
                    .warn(_)(s"Failed to initialise TPLink Bulb '$name''")
                    .as(List.empty[((NonEmptyString, DeviceType), TplinkDevice[F])])
                )
              case _ => F.pure(List.empty[((NonEmptyString, DeviceType), TplinkDevice[F])])
            }
            _ <- trace.put("device.count", devices.size)
          } yield (Map.empty[DiscoveredDeviceKey, Map[String, String]], devices.toMap)
        }
      }

    val onDeviceUpdate: Pipe[F, TplinkDevice[F], Unit] =
      _.evalMap { device =>
        TplinkRemoteConfigSource.deviceToRemote(config.remoteName, device)
      }.unNone.map(ConfigEvent.RemoteAddedEvent(_, eventSource))

    val switchAdded: Pipe[F, TplinkDevice[F], Unit] = _.evalMap { device =>
      device.getState
        .map { state =>
          val key = SwitchKey(device.device, device.name)
          List(SwitchEvent.SwitchAddedEvent(key, device.metadata), SwitchStateUpdateEvent(key, state))
        }
    }.flatMap(Stream.emits).through(switchEventPublisher.pipe)

    val remoteConfigAdded: Pipe[F, TplinkDevice[F], Unit] = _.evalMap { device =>
      TplinkRemoteConfigSource
        .deviceToRemote(config.remoteName, device)
        .map(_.map(ConfigEvent.RemoteAddedEvent(_, eventSource)))
    }.unNone.through(configEventPublisher.pipe)

    val remoteCommandsAdded: Pipe[F, TplinkDevice[F], Unit] = _.flatMap {
      case dev: TplinkDevice.SmartBulb[F] if dev.room.isDefined =>
        Stream.emits(TplinkRemoteControl.commands(dev).keys.toList.map[RemoteEvent] { cmd =>
          RemoteEvent.RemoteLearntCommand(config.remoteName, dev.name, CommandSource, cmd)
        })
      case _ => Stream.empty
    }.through(remoteEventPublisher.pipe)

    val onDeviceAdded: Pipe[F, TplinkDevice[F], Unit] =
      _.broadcastThrough(switchAdded, remoteConfigAdded, remoteCommandsAdded)

    val switchRemoved: Pipe[F, TplinkDevice[F], Unit] = _.map { device =>
      SwitchEvent.SwitchRemovedEvent(SwitchKey(device.device, device.name))
    }.through(switchEventPublisher.pipe)

    val remoteConfigRemoved: Pipe[F, TplinkDevice[F], Unit] = _.evalMap { device =>
      TplinkRemoteConfigSource
        .deviceToRemote(config.remoteName, device)
        .map(_.map(r => ConfigEvent.RemoteRemovedEvent(r.name, eventSource)))
    }.unNone.through(configEventPublisher.pipe)

    val onDeviceRemoved: Pipe[F, TplinkDevice[F], Unit] = _.broadcastThrough(switchRemoved, remoteConfigRemoved)

    Discovery[F, G, (NonEmptyString, DeviceType), TplinkDevice[F]](
      deviceType = DevName,
      config = config.polling,
      doDiscovery = discover,
      onDevicesUpdate = (_, _) => F.unit,
      onDeviceDiscovered = onDeviceAdded,
      onDeviceRemoved = onDeviceRemoved,
      onDeviceUpdate = onDeviceUpdate,
      discoveryEventProducer = discoveryEventPublisher,
      traceParams = device => List("device.name" -> device.name.value, "device.room" -> device.roomName.value),
      k = k
    ).flatMap { disc =>
      Resource
        .make(
          remoteEventPublisher
            .publish1(RemoteEvent.RemoteAddedEvent(config.remoteName, supportsLearning = false, eventSource))
        )(_ => remoteEventPublisher.publish1(RemoteEvent.RemoteRemovedEvent(config.remoteName, eventSource)))
        .map(_ => disc)
    }
  }
}
