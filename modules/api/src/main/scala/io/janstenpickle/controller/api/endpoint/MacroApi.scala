package io.janstenpickle.controller.api.endpoint

import cats.data.NonEmptyList
import cats.effect.Sync
import cats.mtl.ApplicativeHandle
import eu.timepit.refined.types.string.NonEmptyString
import extruder.circe.instances._
import extruder.refined._
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.model.Command
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes}

class MacroApi[F[_]: Sync](macros: Macro[F])(implicit ah: ApplicativeHandle[F, ControlError]) extends Common[F] {
  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / "send" / mac =>
      refineOrBadReq(mac)(m => Ok(macros.executeMacro(m)))
    case req @ POST -> Root / "submit" / mac =>
      refineOrBadReq(mac)(name => req.decode[NonEmptyList[Command]](commands => Ok(macros.storeMacro(name, commands))))
    case GET -> Root => Ok(macros.listMacros)
  }
}
