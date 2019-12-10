package io.janstenpickle.controller.tplink

import cats.Monad
import cats.instances.option._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}

object TplinkDeviceRename {
  def apply[F[_]: Monad](discovery: TplinkDiscovery[F]): DeviceRename[F] = new DeviceRename[F] {
    override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]] =
      discovery.devices.flatMap(
        _.devices
          .collectFirst {
            case ((name, t), dev) if name.value == k.deviceId && s"$DevName-${t.model.value}" == k.deviceType => dev
          }
          .traverse(_.rename(v.name, v.room) *> discovery.reinit)
      )

    override def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]] = discovery.devices.map(_.unmapped)

    override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] =
      discovery.devices.map(_.devices.map {
        case ((name, t), dev) =>
          DiscoveredDeviceKey(name.value, s"$DevName-${t.model.value}") -> DiscoveredDeviceValue(dev.name, dev.room)
      })
  }
}
