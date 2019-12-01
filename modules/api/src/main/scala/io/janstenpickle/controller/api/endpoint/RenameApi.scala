package io.janstenpickle.controller.api.endpoint

import cats.effect.Sync
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import extruder.circe.instances._
import extruder.refined._
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import natchez.Trace
import org.http4s.HttpRoutes

class RenameApi[F[_]: Sync](rename: DeviceRename[F])(implicit trace: Trace[F]) extends Common[F] {
  import RenameApi._

  val routes: HttpRoutes[F] = HttpRoutes.of {
    case GET -> Root / "devices" / "unassigned" =>
      trace.span("unassignedDevices") {
        Ok(rename.unassigned.map(_.toList.map((UnassignedDevice.apply _).tupled)))
      }
    case GET -> Root / "devices" / "assigned" =>
      trace.span("assignedDevices") {
        Ok(rename.assigned.map(_.toList.map((AssignedDevice.apply _).tupled)))
      }
    case req @ PUT -> Root / "devices" / "rename" / t / name =>
      trace.span("renameDevice") {
        trace.put("device.type" -> t, "device.name" -> name) *> req
          .as[DiscoveredDeviceValue]
          .flatMap(rename.rename(DiscoveredDeviceKey(name, t), _))
          .flatMap(_.fold(NotFound())(_ => Ok()))
      }
  }
}

object RenameApi {
  case class UnassignedDevice(key: DiscoveredDeviceKey, metadata: Map[String, String])
  case class AssignedDevice(key: DiscoveredDeviceKey, value: DiscoveredDeviceValue)
}
