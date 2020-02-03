package io.janstenpickle.controller.kodi

import cats.Monad
import cats.effect.Clock
import cats.syntax.apply._
import cats.syntax.functor._
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.DeviceDiscoveryEvent
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import natchez.Trace

object KodiDeviceRename {
  def apply[F[_]: Trace: Clock](
    discovery: KodiDiscovery[F],
    nameMapping: WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    eventPublisher: EventPublisher[F, DeviceDiscoveryEvent]
  )(implicit F: Monad[F]): DeviceRename[F] =
    DeviceRename.evented(
      DeviceRename
        .traced(
          new DeviceRename[F] {
            override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]] =
              if (k.deviceType == DeviceName) (nameMapping.upsert(k, v) *> discovery.reinit).map(Some(_))
              else F.pure(None)

            override def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]] =
              discovery.devices.map(_.unmapped)

            override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] =
              discovery.devices.map(_.devices.map {
                case (_, v) => v.key -> DiscoveredDeviceValue(v.name, Some(v.room))
              })
          },
          "kodi"
        ),
      eventPublisher
    )
}
