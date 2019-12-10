package io.janstenpickle.controller.discovery

import cats.instances.option._
import cats.instances.unit._
import cats.kernel.Monoid
import cats.syntax.semigroup._
import cats.{Applicative, Parallel}
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}

trait DeviceRename[F[_]] {
  def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]]
  def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]]
  def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]]
}

object DeviceRename {
  def empty[F[_]](implicit F: Applicative[F]): DeviceRename[F] = new DeviceRename[F] {
    override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]] = F.pure(None)

    override def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]] = F.pure(Map.empty)

    override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] = F.pure(Map.empty)
  }

  def combined[F[_]: Parallel](x: DeviceRename[F], y: DeviceRename[F]): DeviceRename[F] = new DeviceRename[F] {
    override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]] =
      Parallel.parMap2(x.rename(k, v), y.rename(k, v))(_ |+| _)

    override def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]] =
      Parallel.parMap2(x.unassigned, y.unassigned)(_ ++ _)

    override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] =
      Parallel.parMap2(x.assigned, y.assigned)(_ ++ _)
  }

  implicit def deviceRenameMonoid[F[_]: Applicative: Parallel]: Monoid[DeviceRename[F]] = new Monoid[DeviceRename[F]] {
    override def empty: DeviceRename[F] = DeviceRename.empty[F]

    override def combine(x: DeviceRename[F], y: DeviceRename[F]): DeviceRename[F] = DeviceRename.combined[F](x, y)
  }
}
