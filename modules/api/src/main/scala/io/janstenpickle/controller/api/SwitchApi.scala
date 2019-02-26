package io.janstenpickle.controller.api

import cats.data.EitherT
import cats.effect.Sync
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.refined._
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.switch.{State, Switches}
import org.http4s.circe.jsonEncoderOf
import org.http4s.{EntityEncoder, HttpRoutes}

class SwitchApi[F[_]: Sync](switches: Switches[EitherT[F, ControlError, ?]]) extends Common[F] {
  implicit val switchesEncoder: EntityEncoder[F, List[NonEmptyString]] = jsonEncoderOf[F, List[NonEmptyString]]

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / name / State.On.value =>
      refineOrBadReq(name)(n => handleControlError(switches.switchOn(n)))
    case POST -> Root / name / State.Off.value =>
      refineOrBadReq(name)(n => handleControlError(switches.switchOff(n)))
    case POST -> Root / name / "toggle" =>
      refineOrBadReq(name)(n => handleControlError(switches.toggle(n)))
    case GET -> Root / name / "state" =>
      refineOrBadReq(name)(n => handleControlError(switches.getState(n).map(_.value)))
    case GET -> Root => Ok(switches.list)
  }
}
