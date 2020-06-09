package io.janstenpickle.controller.events.discovery

import cats.Applicative
import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.flatMap._
import cats.syntax.functor._
import fs2.Stream
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.events.syntax.all._
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.event.{CommandEvent, DeviceDiscoveryEvent}
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}

import scala.concurrent.duration._

object EventDrivenDeviceRename {
  def apply[F[_]: Concurrent: Timer](
    discoveryEvents: EventSubscriber[F, DeviceDiscoveryEvent],
    commandPublisher: EventPublisher[F, CommandEvent],
    commandTimeout: FiniteDuration
  ): Resource[F, DeviceRename[F]] = {
    def listen(
      unmapped: Ref[F, Map[DiscoveredDeviceKey, Map[String, String]]],
      mapped: Ref[F, Map[DiscoveredDeviceKey, DiscoveredDeviceValue]]
    ) =
      discoveryEvents.subscribe.evalMap {
        case DeviceDiscoveryEvent.UnmappedDiscovered(key, metadata) =>
          unmapped.update(_.updated(key, metadata)) >> mapped.update(_ - key)
        case DeviceDiscoveryEvent.DeviceDiscovered(key, value) =>
          unmapped.update(_ - key) >> mapped.update(_.updated(key, value))
        case DeviceDiscoveryEvent.DeviceRename(key, value) =>
          unmapped.update(_ - key) >> mapped.update(_.updated(key, value))
        case DeviceDiscoveryEvent.DeviceRemoved(key) => unmapped.update(_ - key) >> mapped.update(_ - key)
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
      new DeviceRename[F] {
        override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]] = {
          lazy val doRename =
            discoveryEvents.waitFor(commandPublisher.publish1(CommandEvent.RenameDeviceCommand(k, v)), commandTimeout) {
              case DeviceDiscoveryEvent.DeviceRename(key, value) => key == k && value == v
            }

          (for {
            um <- unmapped.get
            m <- mapped.get
          } yield (um.keySet ++ m.keySet).contains(k)).ifM(doRename.map(Some(_)), Applicative[F].pure(None))
        }

        override def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]] = unmapped.get

        override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] = mapped.get
      }
  }
}
