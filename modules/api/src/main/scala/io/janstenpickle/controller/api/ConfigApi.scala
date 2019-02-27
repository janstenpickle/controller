package io.janstenpickle.controller.api

import cats.effect.Sync
import cats.syntax.flatMap._
import extruder.circe.instances._
import extruder.refined._
import io.janstenpickle.controller.configsource.{ActivityConfigSource, ButtonConfigSource, RemoteConfigSource}
import io.janstenpickle.controller.model._
import org.http4s.{EntityEncoder, HttpRoutes}

class ConfigApi[F[_]: Sync](
  activity: ActivityConfigSource[F],
  button: ButtonConfigSource[F],
  remote: RemoteConfigSource[F]
) extends Common[F] {

  implicit val activitiesEncoder: EntityEncoder[F, Activities] =
    extruderEncoder[Activities]
  implicit val remotesEncoder: EntityEncoder[F, Remotes] = extruderEncoder[Remotes]
  implicit val buttonsEncoder: EntityEncoder[F, Buttons] = extruderEncoder[Buttons]

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "activities" => activity.getActivities.flatMap(Ok(_))
    case GET -> Root / "remotes" => remote.getRemotes.flatMap(Ok(_))
    case GET -> Root / "buttons" => button.getCommonButtons.flatMap(Ok(_))
  }
}
