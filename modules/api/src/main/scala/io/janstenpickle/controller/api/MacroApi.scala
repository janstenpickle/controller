package io.janstenpickle.controller.api

import cats.data.{EitherT, NonEmptyList}
import cats.effect.Sync
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.`macro`.Macro
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes}
import extruder.circe.instances._
import extruder.refined._
import io.janstenpickle.controller.api.error.ControlError

class MacroApi[F[_]: Sync](macros: Macro[EitherT[F, ControlError, ?]]) extends Common[F] {
  implicit val commandsDecoder: EntityDecoder[F, NonEmptyList[Command]] = extruderDecoder[NonEmptyList[Command]]
  implicit val macrosEncoder: EntityEncoder[F, List[NonEmptyString]] = extruderEncoder[List[NonEmptyString]]

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / "send" / mac =>
      refineOrBadReq(mac)(m => handleControlError(macros.executeMacro(m)))
    case req @ POST -> Root / "submit" / mac =>
      refineOrBadReq(mac)(
        name => req.decode[NonEmptyList[Command]](commands => handleControlError(macros.storeMacro(name, commands)))
      )
    case GET -> Root => handleControlError(macros.listMacros)
  }
}
