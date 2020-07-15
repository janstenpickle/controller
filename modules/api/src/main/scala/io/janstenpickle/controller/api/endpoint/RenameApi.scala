package io.janstenpickle.controller.api.endpoint

import cats.effect.Sync
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.circe.generic.auto._
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.model.{DiscoveredDeviceKey, DiscoveredDeviceValue}
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue
import org.http4s.HttpRoutes

class RenameApi[F[_]: Sync](rename: DeviceRename[F])(implicit trace: Trace[F]) extends Common[F] {
  import RenameApi._

  val routes: HttpRoutes[F] = HttpRoutes.of {
    case GET -> Root / "devices" / "unassigned" =>
      trace.span("api.unassigned.devices") {
        Ok(rename.unassigned.map(_.toList.map((UnassignedDevice.apply _).tupled)))
      }
    case GET -> Root / "devices" / "assigned" =>
      trace.span("api.assigned.devices") {
        Ok(rename.assigned.map(_.toList.map((AssignedDevice.apply _).tupled)))
      }
    case req @ PUT -> Root / "devices" / "rename" / t / name =>
      trace.span("api.rename.device") {
        trace.putAll("device.type" -> t, "device.name" -> name) *> req
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
