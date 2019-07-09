package io.janstenpickle.controller.api.endpoint

import cats.effect.Sync
import cats.mtl.{ApplicativeHandle, FunctorRaise}
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model.Activities
import org.http4s.HttpRoutes

class ActivityApi[F[_]: Sync](activities: Activity[F], activitySource: ConfigSource[F, Activities])(
  implicit fr: FunctorRaise[F, ControlError],
  ah: ApplicativeHandle[F, ControlError]
) extends Common[F] {
  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / room =>
      refineOrBadReq(room)(r => Ok(activities.getActivity(r).map(_.fold("")(_.value))))
    case req @ POST -> Root / room =>
      refineOrBadReq(room) { r =>
        req.decode[String](refineOrBadReq(_) { activity =>
          activitySource.getConfig
            .flatMap { acts =>
              if (acts.activities.map(_.name).contains(activity)) activities.setActivity(r, activity)
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
