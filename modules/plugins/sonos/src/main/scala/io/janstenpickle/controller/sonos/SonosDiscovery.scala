package io.janstenpickle.controller.sonos

import cats.data.OptionT
import cats.effect._
import cats.instances.list._
import cats.instances.string._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{Eq, Parallel}
import com.vmichalak.sonoscontroller
import com.vmichalak.sonoscontroller.{CommandBuilder, SonosDevice => JSonosDevice}
import eu.timepit.refined.types.string.NonEmptyString
import fs2.{Pipe, Stream}
import io.janstenpickle.controller.discovery.Discovery
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.ConfigEvent._
import io.janstenpickle.controller.model.event.SwitchEvent.{
  SwitchAddedEvent,
  SwitchRemovedEvent,
  SwitchStateUpdateEvent
}
import io.janstenpickle.controller.model.event.{ConfigEvent, DeviceDiscoveryEvent, RemoteEvent, SwitchEvent}
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import io.janstenpickle.controller.poller.Empty
import io.janstenpickle.controller.sonos.config.SonosRemoteConfigSource
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.collection.JavaConverters._
import scala.xml._

object SonosDiscovery {
  val eventSource: String = "sonos"

  def snakify(name: String): String =
    name
      .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
      .replaceAll("([a-z\\d])([A-Z])", "$1_$2")
      .replaceAll("\\s+", "_")
      .toLowerCase

  implicit def sonosDeviceEq[F[_]]: Eq[SonosDevice[F]] = Eq.by(_.name)
  implicit val nesEq: Eq[NonEmptyString] = Eq.by(_.value)

  implicit def empty[F[_]]: Empty[(Map[NonEmptyString, SonosDevice[F]], Map[NonEmptyString, Long])] =
    Empty((Map.empty[NonEmptyString, SonosDevice[F]], Map.empty[NonEmptyString, Long]))

  def polling[F[_]: Parallel, G[_]: Async](
    config: SonosComponents.Config,
    remoteEventPublisher: EventPublisher[F, RemoteEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    configEventPublisher: EventPublisher[F, ConfigEvent],
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit F: Async[F], trace: Trace[F], provide: Provide[G, F, Span[G]]): Resource[F, SonosDiscovery[F]] =
    Resource.eval(Slf4jLogger.create[F]).flatMap { logger =>
      Resource.eval(Ref.of[F, Map[String, SonosDevice[F]]](Map.empty)).flatMap { devicesRef =>
        val deviceUpdate: SonosDevice[F] => F[ConfigEvent] = dev =>
          SonosRemoteConfigSource
            .deviceToRemote(config.remote, config.activity.name, config.allRooms, dev)
            .map(RemoteAddedEvent(_, eventSource))

        def deviceName(device: JSonosDevice) =
          OptionT(Sync[F].blocking[Option[String]](Option(device.getSpeakerInfo.getIpAddress).filter(_.nonEmpty)))
            .semiflatMap { ip =>
              Sync[F]
                .blocking[String](
                  CommandBuilder
                    .zoneGroupTopology("GetZoneGroupState")
                    .executeOn(ip)
                )
                .flatMap { resp =>
                  F.delay((XML.loadString(resp) \\ "ZoneGroups" \ "ZoneGroup").flatMap { zoneGroup =>
                    val members = (zoneGroup \ "ZoneGroupMember").flatMap { zoneGroupMember =>
                      if (zoneGroupMember \@ "Invisible" != "1")
                        Some(zoneGroupMember \@ "UUID" -> zoneGroupMember \@ "ZoneName")
                      else None
                    }

                    if (members.isEmpty) None
                    else Some(zoneGroup \@ "Coordinator" -> members)
                  }.toMap)
                }
            }

        def discover: F[Map[NonEmptyString, SonosDevice[F]]] = trace.span("sonos.discover") {
          Sync[F]
            .blocking[List[JSonosDevice]](sonoscontroller.SonosDiscovery.discover().asScala.toList)
            .flatTap { devices =>
              trace.put("device.count", devices.size)
            }
            .flatMap { discovered =>
              discovered
                .parFlatTraverse { device =>
                  trace.span("sonos.read.device") {
                    (for {
                      id <- trace.span("sonos.get.id") {
                        Sync[F].blocking(device.getSpeakerInfo.getLocalUID)
                      }
                      zoneInfo <- deviceName(device).value
                      name = zoneInfo.flatMap(_.values.flatten.toMap.get(id))
                    } yield (id, name))
                      .flatMap {
                        case (id, Some(name)) if id.nonEmpty && name.nonEmpty =>
                          for {
                            formattedName <- F
                              .fromEither(NonEmptyString.from(snakify(name)).leftMap(new RuntimeException(_)))
                            nonEmptyName <- F.fromEither(NonEmptyString.from(name).leftMap(new RuntimeException(_)))
                            _ <- trace.putAll("device.id" -> id, "device.name" -> name)
                            dev <- SonosDevice[F](
                              id,
                              formattedName,
                              nonEmptyName,
                              config.switchDevice,
                              DiscoveredDeviceKey(id, "sonos"),
                              DiscoveredDeviceValue(formattedName, Some(formattedName)),
                              device,
                              devicesRef,
                              config.commandTimeout,
                              deviceUpdate(_).flatMap(configEventPublisher.publish1),
                              switchEventPublisher.narrow
                            )
                          } yield List(dev.name -> dev)
                        case _ => F.pure(List.empty[(NonEmptyString, SonosDevice[F])])
                      }
                      .handleErrorWith(
                        logger
                          .warn(_)(s"Discovery initialization of Sonos device failed")
                          .as(List.empty[(NonEmptyString, SonosDevice[F])])
                      )

                  }
                }
                .map(_.toMap)
            }
        }

        val removedSwitches: Pipe[F, SonosDevice[F], Unit] = _.flatMap { dev =>
          val removed = SonosSwitchProvider.deviceToSwitches(config.switchDevice, dev, switchEventPublisher.narrow).keys

          Stream.fromIterator[F](removed.iterator, removed.size)
        }.map(SwitchRemovedEvent).through(switchEventPublisher.pipe)

        val removedRemotes: Pipe[F, SonosDevice[F], Unit] = _.evalMap(
          SonosRemoteConfigSource.deviceToRemote(config.remote, config.activityConfig.name, config.allRooms, _)
        ).map(r => RemoteRemovedEvent(r.name, eventSource)).through(configEventPublisher.pipe)

        val onDeviceRemoved: Pipe[F, SonosDevice[F], Unit] = _.broadcastThrough(removedSwitches, removedRemotes)

        val addedSwitches: Pipe[F, SonosDevice[F], Unit] = _.flatMap { dev =>
          val added = SonosSwitchProvider.deviceToSwitches(config.switchDevice, dev, switchEventPublisher.narrow)

          Stream.fromIterator[F](added.iterator, added.size)
        }.evalMap {
            case (key, switch) =>
              switch.getState.map { state =>
                List(SwitchAddedEvent(key, switch.metadata), SwitchStateUpdateEvent(key, state))
              }
          }
          .flatMap(Stream.emits)
          .through(switchEventPublisher.pipe)

        val addedRemotes: Pipe[F, SonosDevice[F], Unit] = _.evalMap { dev =>
          SonosRemoteConfigSource.deviceToRemote(config.remote, config.activityConfig.name, config.allRooms, dev)
        }.map(r => RemoteAddedEvent(r, eventSource)).through(configEventPublisher.pipe)

        val cmds = SonosRemoteControl.commands[F]

        val addedCommands: Pipe[F, SonosDevice[F], Unit] = _.flatMap { dev =>
          Stream.emits(cmds.keys.toList.map[RemoteEvent] { cmd =>
            RemoteEvent.RemoteLearntCommand(config.remote, dev.name, CommandSource, cmd)
          })
        }.through(remoteEventPublisher.pipe)

        val onDeviceDiscovered: Pipe[F, SonosDevice[F], Unit] =
          _.broadcastThrough(addedSwitches, addedRemotes, addedCommands)

        val onDeviceUpdate: Pipe[F, SonosDevice[F], Unit] = _.evalMap(deviceUpdate).through(configEventPublisher.pipe)

        Discovery[F, G, NonEmptyString, SonosDevice[F]](
          deviceType = "sonos",
          config = config.polling,
          doDiscovery = discover.map(Map.empty -> _),
          onDevicesUpdate = (_, data) => devicesRef.set(data._2.values.map(d => d.id -> d).toMap),
          onDeviceDiscovered = onDeviceDiscovered,
          onDeviceRemoved = onDeviceRemoved,
          onDeviceUpdate = onDeviceUpdate,
          discoveryEventProducer = discoveryEventPublisher,
          traceParams = device => List("device.name" -> device.name.value, "device.id" -> device.id),
          k = k
        ).flatMap { disc =>
          Resource
            .make(
              remoteEventPublisher
                .publish1(RemoteEvent.RemoteAddedEvent(config.remote, supportsLearning = false, eventSource))
            )(_ => remoteEventPublisher.publish1(RemoteEvent.RemoteRemovedEvent(config.remote, eventSource)))
            .map(_ => disc)
        }
      }
    }
}
