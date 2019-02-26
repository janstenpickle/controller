package io.janstenpickle.controller.api

import cats.data.EitherT
import cats.effect.Sync
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.api.error.ControlError
import org.http4s.HttpRoutes

class ActivityApi[F[_]: Sync](activities: Activity[EitherT[F, ControlError, ?]]) extends Common[F] {
  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root => handleControlError(activities.getActivity.map(_.fold("")(_.value)))
    case POST -> Root / activity =>
      refineOrBadReq(activity)(a => handleControlError(activities.setActivity(a)))
  }
}
