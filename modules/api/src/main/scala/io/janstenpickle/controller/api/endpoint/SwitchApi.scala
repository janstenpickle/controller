package io.janstenpickle.controller.api.endpoint

import cats.Semigroupal
import cats.data.ValidatedNel
import cats.effect.Sync
import cats.mtl.ApplicativeHandle
import cats.syntax.either._
import cats.syntax.functor._
import eu.timepit.refined._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.http4s.error.ControlError
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.Switches
import org.http4s.{HttpRoutes, Response}

class SwitchApi[F[_]: Sync](switches: Switches[F])(implicit ah: ApplicativeHandle[F, ControlError]) extends Common[F] {
  def refineOrBadReq(device: String, name: String)(
    f: (NonEmptyString, NonEmptyString) => F[Response[F]]
  ): F[Response[F]] =
    Semigroupal
      .map2[ValidatedNel[String, *], NonEmptyString, NonEmptyString, F[Response[F]]](
        refineV[NonEmpty](device).toValidatedNel,
        refineV[NonEmpty](name).toValidatedNel
      )(f)
      .leftMap(errs => BadRequest(errs.toList.mkString(",")))
      .merge

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / State.On.value / device / name =>
      refineOrBadReq(device, name)((d, n) => Ok(switches.switchOn(d, n)))
    case POST -> Root / State.Off.value / device / name =>
      refineOrBadReq(device, name)((d, n) => Ok(switches.switchOff(d, n)))
    case POST -> Root / "toggle" / device / name =>
      refineOrBadReq(device, name)((d, n) => Ok(switches.toggle(d, n)))
    case GET -> Root / device / name =>
      refineOrBadReq(device, name)((d, n) => Ok(switches.getState(d, n).map(_.value)))
    case GET -> Root => Ok(switches.list)
  }
}
