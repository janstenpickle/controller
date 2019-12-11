package io.janstenpickle.controller.discovery

import cats.instances.option._
import cats.instances.unit._
import cats.kernel.Monoid
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.semigroup._
import cats.{Applicative, FlatMap, Parallel}
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import natchez.TraceValue.StringValue
import natchez.{Trace, TraceValue}

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

  def traced[F[_]: FlatMap](underlying: DeviceRename[F], source: String, extraFields: (String, TraceValue)*)(
    implicit trace: Trace[F]
  ): DeviceRename[F] =
    new DeviceRename[F] {
      private def span[A](name: String, fields: (String, TraceValue)*)(fa: F[A]) =
        trace.span[A](name)(trace.put(("source" -> StringValue(source)) :: fields.toList ++ extraFields: _*) *> fa)

      override def rename(k: DiscoveredDeviceKey, v: DiscoveredDeviceValue): F[Option[Unit]] =
        span(
          "renameDevice",
          List[(String, TraceValue)](
            "device.id" -> k.deviceId,
            "device.type" -> k.deviceType,
            "device.name" -> v.name.value
          ) ++ v.room
            .map(r => "device.room" -> StringValue(r.value)): _*
        ) {
          underlying
            .rename(k, v)
            .flatTap(r => trace.put("device.valid" -> r.isDefined))
        }
      override def unassigned: F[Map[DiscoveredDeviceKey, Map[String, String]]] =
        span("getUnassignedDevices") {
          underlying.unassigned.flatTap(d => trace.put("unassigned.count" -> d.size))
        }
      override def assigned: F[Map[DiscoveredDeviceKey, DiscoveredDeviceValue]] =
        span("getAssignedDevices") {
          underlying.assigned.flatTap(d => trace.put("assigned.count" -> d.size))
        }
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
