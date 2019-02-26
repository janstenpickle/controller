package io.janstenpickle.controller.api

import cats.data.{EitherT, NonEmptyList}
import cats.effect.Sync
import io.janstenpickle.controller.model.Command
import io.janstenpickle.controller.`macro`.Macro
import org.http4s.{EntityDecoder, HttpRoutes}
import io.circe.generic.auto._
import io.circe.refined._
import io.janstenpickle.controller.api.error.ControlError
import org.http4s.circe.jsonOf

class MacroApi[F[_]: Sync](view: Macro[EitherT[F, ControlError, ?]]) extends Common[F] {
  implicit val commandsDecoder: EntityDecoder[F, NonEmptyList[Command]] = jsonOf[F, NonEmptyList[Command]]

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / mac / "send" =>
      refineOrBadReq(mac)(m => handleControlError(view.executeMacro(m)))
    case req @ POST -> Root / mac =>
      refineOrBadReq(mac)(
        name => req.decode[NonEmptyList[Command]](commands => handleControlError(view.storeMacro(name, commands)))
      )
  }
}
