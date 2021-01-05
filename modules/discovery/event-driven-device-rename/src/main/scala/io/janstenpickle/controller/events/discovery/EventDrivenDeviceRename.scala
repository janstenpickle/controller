package io.janstenpickle.controller.events.discovery

import cats.Applicative
import cats.effect.syntax.concurrent._
import cats.effect.{BracketThrow, Concurrent, Resource, Timer}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import fs2.Stream
import io.janstenpickle.controller.cache.{Cache, CacheResource}
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.events.syntax.all._
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.event.{CommandEvent, DeviceDiscoveryEvent}
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import io.janstenpickle.trace4cats.model.AttributeValue

import scala.concurrent.duration._

object EventDrivenDeviceRename {
  def apply[F[_]: Concurrent: Timer, G[_]: BracketThrow](
    discoveryEvents: EventSubscriber[F, DeviceDiscoveryEvent],
    commandPublisher: EventPublisher[F, CommandEvent],
    source: String,
    commandTimeout: FiniteDuration,
    k: ResourceKleisli[G, (SpanName, Map[String, String]), Span[G]],
    cacheTimeout: FiniteDuration = 20.minutes
  )(implicit trace: Trace[F], provide: Provide[G, F, Span[G]]): Resource[F, DeviceRename[F]] = {

    def span[A](name: String, key: DiscoveredDeviceKey, extraFields: (String, AttributeValue)*)(f: F[A]): F[A] =
      trace.span(name) {
        trace.putAll(extraFields ++ List[(String, AttributeValue)]("device.id" -> key.deviceId): _*) *> f
      }

    def listen(
      unmapped: Cache[F, DiscoveredDeviceKey, Map[String, String]],
      mapped: Cache[F, DiscoveredDeviceKey, DiscoveredDeviceValue]
    ) =
      discoveryEvents.filterEvent(_.source != source).subscribeEvent.evalMapTrace("discovery.receive", k) {
        case DeviceDiscoveryEvent.UnmappedDiscovered(key, metadata) =>
          span("discovery.discovered.unmapped", key)(unmapped.set(key, metadata) >> mapped.remove(key))
        case DeviceDiscoveryEvent.DeviceDiscovered(key, value) =>
          span(
            "discovery.discovered.mapped",
            key,
            "device.name" -> value.name.value,
            "device.room" -> value.room.fold("")(_.value)
          )(unmapped.remove(key) >> mapped.set(key, value))
        case DeviceDiscoveryEvent.DeviceRename(key, value) =>
          span(
            "discovery.rename",
            key,
            "device.name" -> value.name.value,
            "device.room" -> value.room.fold("")(_.value)
          )(unmapped.remove(key) >> mapped.set(key, value))
        case DeviceDiscoveryEvent.DeviceRemoved(key) =>
          span("discovery.remove", key)(unmapped.remove(key) >> mapped.remove(key))
      }

    def listener(
      unmapped: Cache[F, DiscoveredDeviceKey, Map[String, String]],
      mapped: Cache[F, DiscoveredDeviceKey, DiscoveredDeviceValue]
    ): Resource[F, F[Unit]] =
      Stream
        .retry(listen(unmapped, mapped).compile.drain, 5.seconds, _ + 1.second, Int.MaxValue)
        .compile
        .drain
        .background

    for {
      unmapped <- CacheResource.caffeine[F, DiscoveredDeviceKey, Map[String, String]](cacheTimeout)
      mapped <- CacheResource.caffeine[F, DiscoveredDeviceKey, DiscoveredDeviceValue](cacheTimeout)
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
                um <- unmapped.getAll
                m <- mapped.getAll
              } yield (um.keySet ++ m.keySet).contains(k)).ifM(doRename, Applicative[F].pure(None))
            }

            override def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]] = unmapped.getAll

            override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] = mapped.getAll
          },
          source
        )
  }
}
