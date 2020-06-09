package io.janstenpickle.controller.broadlink

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

object BroadlinkDeviceRename {
  def apply[F[_]: Trace: Clock](
    discovery: BroadlinkDiscovery[F],
    nameMapping: WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    eventPublisher: EventPublisher[F, DeviceDiscoveryEvent]
  )(implicit F: Monad[F]): DeviceRename[F] =
    DeviceRename.evented(
      DeviceRename
        .traced(
          new DeviceRename[F] {
            override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]] =
              if (k.deviceType == devType("remote") || k.deviceType == devType("switch"))
                (nameMapping.upsert(k, v) *> discovery.reinit).map(Some(_))
              else F.pure(None)

            override def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]] =
              discovery.devices.map(_.unmapped)

            override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] =
              discovery.devices.map(_.devices.map {
                case (_, BroadlinkDevice.Remote(v)) =>
                  DiscoveredDeviceKey(formatDeviceId(v.mac), devType("remote")) -> DiscoveredDeviceValue(v.name, None)
                case (_, BroadlinkDevice.Switch(v)) =>
                  DiscoveredDeviceKey(formatDeviceId(v.mac), devType("switch")) -> DiscoveredDeviceValue(v.name, None)
              })
          },
          "broadlink"
        ),
      eventPublisher
    )
}
