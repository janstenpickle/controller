package io.janstenpickle.controller.api

import cats.data.EitherT
import cats.effect.Sync
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.configsource.ActivityConfigSource
import org.http4s.HttpRoutes

class ActivityApi[F[_]: Sync](
  activities: Activity[EitherT[F, ControlError, ?]],
  activitySource: ActivityConfigSource[F]
) extends Common[F] {
  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root =>
      handleControlError(activities.getActivity.map(_.fold("")(_.value)))
    case POST -> Root / activity =>
      refineOrBadReq(activity) { a =>
        handleControlError(EitherT.liftF(activitySource.getActivities).flatMap { acts =>
          if (acts.activities.map(_.name).contains(a)) activities.setActivity(a)
          else
            EitherT
              .leftT[F, Unit](ControlError.Missing(s"Activity '$a' is not configured, please check your configuration"))
        })
      }
  }
}
