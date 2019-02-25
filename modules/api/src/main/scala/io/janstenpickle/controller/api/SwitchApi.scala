package io.janstenpickle.controller.api

import cats.data.EitherT
import cats.effect.Sync
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.switch.State
import io.janstenpickle.controller.view.View
import org.http4s.HttpRoutes

class SwitchApi[F[_]: Sync](view: View[EitherT[F, ControlError, ?]]) extends Common[F] {
  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / name / State.On.value =>
      refineOrBadReq(name)(n => handleControlError(view.switchOn(n)))
    case POST -> Root / name / State.Off.value =>
      refineOrBadReq(name)(n => handleControlError(view.switchOff(n)))
    case POST -> Root / name / "toggle" =>
      refineOrBadReq(name)(n => handleControlError(view.toggle(n)))
    case GET -> Root / name =>
      refineOrBadReq(name)(n => handleControlError(view.getState(n).map(_.value)))
  }
}
