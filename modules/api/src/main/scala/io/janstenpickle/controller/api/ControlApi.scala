package io.janstenpickle.controller.api

import cats.data.EitherT
import cats.effect.Sync
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.view.View
import org.http4s.HttpRoutes
import org.http4s.server.Router

object ControlApi {
  def apply[F[_]: Sync](view: View[EitherT[F, ControlError, ?]]): HttpRoutes[F] =
    Router(
      "remote" -> new RemoteApi[F](view).routes,
      "switch" -> new SwitchApi[F](view).routes,
      "macro" -> new MacroApi[F](view).routes,
      "activity" -> new ActivityApi[F](view).routes
    )
}
