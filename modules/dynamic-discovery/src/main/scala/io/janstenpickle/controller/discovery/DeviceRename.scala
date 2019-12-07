package io.janstenpickle.controller.discovery

import cats.{Applicative, Parallel}
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import cats.syntax.semigroup._
import cats.instances.unit._
import cats.kernel.Monoid

trait DeviceRename[F[_]] {
  def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Unit]
  def unassigned: F[Set[DiscoveredDeviceKey]]
  def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]]
}

object DeviceRename {
  def empty[F[_]](implicit F: Applicative[F]): DeviceRename[F] = new DeviceRename[F] {
    override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Unit] = F.unit

    override def unassigned: F[Set[DiscoveredDeviceKey]] = F.pure(Set.empty)

    override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] = F.pure(Map.empty)
  }

  def combined[F[_]: Parallel](x: DeviceRename[F], y: DeviceRename[F]): DeviceRename[F] = new DeviceRename[F] {
    override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Unit] =
      Parallel.parMap2(x.rename(k, v), y.rename(k, v))(_ |+| _)

    override def unassigned: F[Set[DiscoveredDeviceKey]] = Parallel.parMap2(x.unassigned, y.unassigned)(_ ++ _)

    override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] =
      Parallel.parMap2(x.assigned, y.assigned)(_ ++ _)
  }

  implicit def deviceRenameMonoid[F[_]: Applicative: Parallel]: Monoid[DeviceRename[F]] = new Monoid[DeviceRename[F]] {
    override def empty: DeviceRename[F] = DeviceRename.empty[F]

    override def combine(x: DeviceRename[F], y: DeviceRename[F]): DeviceRename[F] = DeviceRename.combined[F](x, y)
  }
}
