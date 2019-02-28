package io.janstenpickle.controller.api

import cats.data.EitherT
import cats.effect.Sync
import extruder.circe.instances._
import extruder.refined._
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.api.view.ConfigView
import io.janstenpickle.controller.model._
import org.http4s.{EntityEncoder, HttpRoutes}

class ConfigApi[F[_]: Sync](view: ConfigView[EitherT[F, ControlError, ?]]) extends Common[F] {

  implicit val activitiesEncoder: EntityEncoder[F, Activities] =
    extruderEncoder[Activities]
  implicit val remotesEncoder: EntityEncoder[F, Remotes] = extruderEncoder[Remotes]
  implicit val buttonsEncoder: EntityEncoder[F, Buttons] = extruderEncoder[Buttons]

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "activities" => handleControlError(view.getActivities)
    case GET -> Root / "remotes" => handleControlError(view.getRemotes)
    case GET -> Root / "buttons" => handleControlError(view.getCommonButtons)
  }
}
