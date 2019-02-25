package io.janstenpickle.controller.api

import cats.data.EitherT
import cats.effect.Sync
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.generic.auto._
import io.circe.refined._
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model.{Activity, Button, Remote}
import org.http4s.circe.jsonEncoderOf
import org.http4s.{EntityEncoder, HttpRoutes}

class ConfigApi[F[_]: Sync](config: ConfigSource[EitherT[F, ControlError, ?]]) extends Common[F] {

  implicit val activitiesEncoder: EntityEncoder[F, Map[NonEmptyString, Activity]] =
    jsonEncoderOf[F, Map[NonEmptyString, Activity]]
  implicit val remotesEncoder: EntityEncoder[F, List[Remote]] = jsonEncoderOf[F, List[Remote]]
  implicit val buttonsEncoder: EntityEncoder[F, List[Button]] = jsonEncoderOf[F, List[Button]]

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "activities" => handleControlError(config.getActivities)
    case GET -> Root / "remotes" => handleControlError(config.getRemotes)
    case GET -> Root / "buttons" => handleControlError(config.getCommonButtons)
  }
}
