package io.janstenpickle.controller.api

import cats.Semigroupal
import cats.data.{EitherT, ValidatedNel}
import cats.effect.Sync
import cats.syntax.either._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined._
import extruder.circe.instances._
import extruder.refined._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.switch.{State, SwitchKey, Switches}
import org.http4s.{EntityEncoder, HttpRoutes, Response}

class SwitchApi[F[_]: Sync](switches: Switches[EitherT[F, ControlError, ?]]) extends Common[F] {
  implicit val switchesEncoder: EntityEncoder[F, List[SwitchKey]] = extruderEncoder[List[SwitchKey]]

  def refineOrBadReq(device: String, name: String)(
    f: (NonEmptyString, NonEmptyString) => F[Response[F]]
  ): F[Response[F]] =
    Semigroupal
      .map2[ValidatedNel[String, ?], NonEmptyString, NonEmptyString, F[Response[F]]](
        refineV[NonEmpty](device).toValidatedNel,
        refineV[NonEmpty](name).toValidatedNel
      )(f)
      .leftMap(errs => BadRequest(errs.toList.mkString(",")))
      .merge

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / State.On.value / device / name =>
      refineOrBadReq(device, name)((d, n) => handleControlError(switches.switchOn(d, n)))
    case POST -> Root / State.Off.value / device / name =>
      refineOrBadReq(device, name)((d, n) => handleControlError(switches.switchOff(d, n)))
    case POST -> Root / "toggle" / device / name =>
      refineOrBadReq(device, name)((d, n) => handleControlError(switches.toggle(d, n)))
    case GET -> Root / device / name =>
      refineOrBadReq(device, name)((d, n) => handleControlError(switches.getState(d, n).map(_.value)))
    case GET -> Root => handleControlError(switches.list)
  }
}
