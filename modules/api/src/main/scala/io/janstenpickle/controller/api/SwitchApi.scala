package io.janstenpickle.controller.api

import cats.data.EitherT
import cats.effect.Sync
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.switch.{State, Switches}
import org.http4s.HttpRoutes

class SwitchApi[F[_]: Sync](switches: Switches[EitherT[F, ControlError, ?]]) extends Common[F] {
  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / name / State.On.value =>
      refineOrBadReq(name)(n => handleControlError(switches.switchOn(n)))
    case POST -> Root / name / State.Off.value =>
      refineOrBadReq(name)(n => handleControlError(switches.switchOff(n)))
    case POST -> Root / name / "toggle" =>
      refineOrBadReq(name)(n => handleControlError(switches.toggle(n)))
    case GET -> Root / name =>
      refineOrBadReq(name)(n => handleControlError(switches.getState(n).map(_.value)))
  }
}
