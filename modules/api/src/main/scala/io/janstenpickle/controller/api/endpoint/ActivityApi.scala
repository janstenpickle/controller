package io.janstenpickle.controller.api.endpoint

import cats.effect.Sync
import cats.mtl.{ApplicativeHandle, FunctorRaise}
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.circe.refined._
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.http4s.error.ControlError
import io.janstenpickle.controller.model.{Activity => ActivityModel}
import org.http4s.{EntityDecoder, HttpRoutes}

class ActivityApi[F[_]: Sync](activities: Activity[F], activitySource: ConfigSource[F, String, ActivityModel])(
  implicit fr: FunctorRaise[F, ControlError],
  ah: ApplicativeHandle[F, ControlError]
) extends Common[F] {
  implicit val stringDecoder: EntityDecoder[F, String] = EntityDecoder.text[F]

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / room =>
      refineOrBadReq(room)(r => Ok(activities.getActivity(r).map(_.fold("")(_.value))))
    case req @ POST -> Root / room =>
      refineOrBadReq(room) { r =>
        req.decode[String](refineOrBadReq(_) { activity =>
          activitySource.getConfig
            .flatMap { acts =>
              if (acts.values.values.map(_.name).toSet.contains(activity)) activities.setActivity(r, activity)
              else
                fr.raise[Unit](
                  ControlError.Missing(s"Activity '$activity' is not configured, please check your configuration")
                )
            }
            .flatMap(Ok(_))
        })
      }
  }
}
