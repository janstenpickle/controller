package io.janstenpickle.controller.broadlink

import cats.Applicative
import cats.syntax.apply._
import cats.syntax.functor._
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}

object BroadlinkDeviceRename {
  def apply[F[_]](
    discovery: BroadlinkDiscovery[F],
    nameMapping: WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue]
  )(implicit F: Applicative[F]): DeviceRename[F] = new DeviceRename[F] {
    override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]] =
      if (k.deviceType == devType("remote") || k.deviceType == devType("switch"))
        (nameMapping.upsert(k, v) *> discovery.reinit).map(Some(_))
      else F.pure(None)

    override def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]] = discovery.devices.map(_.unmapped)

    override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] =
      discovery.devices.map(_.devices.map {
        case (_, Right(v)) =>
          DiscoveredDeviceKey(formatDeviceId(v.mac), devType("remote")) -> DiscoveredDeviceValue(v.name, None)
        case (_, Left(v)) =>
          DiscoveredDeviceKey(formatDeviceId(v.mac), devType("switch")) -> DiscoveredDeviceValue(v.name, None)
      })
  }
}
