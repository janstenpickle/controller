package io.janstenpickle.controller.kodi

import cats.Applicative
import cats.syntax.apply._
import cats.syntax.functor._
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}

object KodiDeviceRename {
  def apply[F[_]](
    discovery: KodiDiscovery[F],
    nameMapping: WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue]
  )(implicit F: Applicative[F]): DeviceRename[F] = new DeviceRename[F] {
    override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]] =
      if (k.deviceType == DeviceName) (nameMapping.upsert(k, v) *> discovery.reinit).map(Some(_))
      else F.pure(None)

    override def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]] = discovery.devices.map(_.unmapped)

    override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] =
      discovery.devices.map(_.devices.map { case (_, v) => v.key -> DiscoveredDeviceValue(v.name, Some(v.room)) })
  }
}
