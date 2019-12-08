package io.janstenpickle.controller.api.endpoint

import cats.effect.Sync
import cats.syntax.apply._
import cats.syntax.flatMap._
import extruder.circe.instances._
import extruder.refined._
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import natchez.Trace
import org.http4s.HttpRoutes

class RenameApi[F[_]: Sync](rename: DeviceRename[F])(implicit trace: Trace[F]) extends Common[F] {

  val routes: HttpRoutes[F] = HttpRoutes.of {
    case GET -> Root / "devices" / "unassigned" =>
      trace.span("unassignedDevices") {
        Ok(rename.unassigned)
      }
    case GET -> Root / "devices" / "assigned" =>
      trace.span("assignedDevices") {
        import io.janstenpickle.controller.configsource.extruder.ExtruderDiscoveryMappingConfigSource.keyShow

        Ok(rename.assigned)
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
