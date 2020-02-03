package io.janstenpickle.controller.tplink

import cats.Monad
import cats.effect.Clock
import cats.instances.option._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.DeviceDiscoveryEvent
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import natchez.Trace

object TplinkDeviceRename {
  def apply[F[_]: Monad: Trace: Clock](
    discovery: TplinkDiscovery[F],
    eventPublisher: EventPublisher[F, DeviceDiscoveryEvent]
  ): DeviceRename[F] =
    DeviceRename.evented(
      DeviceRename
        .traced(
          new DeviceRename[F] {
            override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]] =
              discovery.devices.flatMap(
                _.devices
                  .collectFirst {
                    case ((name, t), dev) if name.value == k.deviceId && s"$DevName-${t.model.value}" == k.deviceType =>
                      dev
                  }
                  .traverse(_.rename(v.name, v.room) *> discovery.reinit)
              )

            override def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]] =
              discovery.devices.map(_.unmapped)

            override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] =
              discovery.devices.map(_.devices.map {
                case ((name, t), dev) =>
                  DiscoveredDeviceKey(name.value, s"$DevName-${t.model.value}") -> DiscoveredDeviceValue(
                    dev.name,
                    dev.room
                  )
              })
          },
          "tplink"
        ),
      eventPublisher
    )
}
