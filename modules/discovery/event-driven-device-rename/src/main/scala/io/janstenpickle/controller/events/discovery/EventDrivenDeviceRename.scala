package io.janstenpickle.controller.events.discovery

import cats.Applicative
import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import fs2.Stream
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.events.syntax.all._
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.event.{CommandEvent, DeviceDiscoveryEvent}
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import natchez.{Trace, TraceValue}

import scala.concurrent.duration._

object EventDrivenDeviceRename {
  def apply[F[_]: Concurrent: Timer, G[_]](
    discoveryEvents: EventSubscriber[F, DeviceDiscoveryEvent],
    commandPublisher: EventPublisher[F, CommandEvent],
    source: String,
    commandTimeout: FiniteDuration
  )(
    implicit trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, DeviceRename[F]] = {

    def span[A](name: String, key: DiscoveredDeviceKey, extraFields: (String, TraceValue)*)(f: F[A]): F[A] =
      trace.span(name) {
        trace.put(extraFields ++ List[(String, TraceValue)]("device.id" -> key.deviceId): _*) *> f
      }

    def listen(
      unmapped: Ref[F, Map[DiscoveredDeviceKey, Map[String, String]]],
      mapped: Ref[F, Map[DiscoveredDeviceKey, DiscoveredDeviceValue]]
    ) =
      discoveryEvents.filterEvent(_.source != source).subscribeEvent.evalMapTrace("discovery.receive") {
        case DeviceDiscoveryEvent.UnmappedDiscovered(key, metadata) =>
          span("discovery.discovered.unmapped", key)(
            unmapped.update(_.updated(key, metadata)) >> mapped.update(_ - key)
          )
        case DeviceDiscoveryEvent.DeviceDiscovered(key, value) =>
          span(
            "discovery.discovered.mapped",
            key,
            "device.name" -> value.name.value,
            "device.room" -> value.room.fold("")(_.value)
          )(unmapped.update(_ - key) >> mapped.update(_.updated(key, value)))
        case DeviceDiscoveryEvent.DeviceRename(key, value) =>
          span(
            "discovery.rename",
            key,
            "device.name" -> value.name.value,
            "device.room" -> value.room.fold("")(_.value)
          )(unmapped.update(_ - key) >> mapped.update(_.updated(key, value)))
        case DeviceDiscoveryEvent.DeviceRemoved(key) =>
          span("discovery.remove", key)(unmapped.update(_ - key) >> mapped.update(_ - key))
      }

    def listener(
      unmapped: Ref[F, Map[DiscoveredDeviceKey, Map[String, String]]],
      mapped: Ref[F, Map[DiscoveredDeviceKey, DiscoveredDeviceValue]]
    ): Resource[F, F[Unit]] =
      Stream
        .retry(listen(unmapped, mapped).compile.drain, 5.seconds, _ + 1.second, Int.MaxValue)
        .compile
        .drain
        .background

    for {
      unmapped <- Resource.liftF(Ref.of[F, Map[DiscoveredDeviceKey, Map[String, String]]](Map.empty))
      mapped <- Resource.liftF(Ref.of[F, Map[DiscoveredDeviceKey, DiscoveredDeviceValue]](Map.empty))
      _ <- listener(unmapped, mapped)
    } yield
      DeviceRename
        .traced(
          new DeviceRename[F] {
            override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]] = {
              lazy val doRename =
                discoveryEvents
                  .waitFor(commandPublisher.publish1(CommandEvent.RenameDeviceCommand(k, v)), commandTimeout) {
                    case DeviceDiscoveryEvent.DeviceRename(key, value) => key == k && value == v
                  }

              (for {
                um <- unmapped.get
                m <- mapped.get
              } yield (um.keySet ++ m.keySet).contains(k)).ifM(doRename, Applicative[F].pure(None))
            }

            override def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]] = unmapped.get

            override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] = mapped.get
          },
          source
        )
  }
}
