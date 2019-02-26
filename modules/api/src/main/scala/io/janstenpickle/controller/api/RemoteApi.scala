package io.janstenpickle.controller.api

import cats.Semigroupal
import cats.data.{EitherT, ValidatedNel}
import cats.effect.Sync
import cats.syntax.either._
import eu.timepit.refined._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.remotecontrol.RemoteControls
import org.http4s.{HttpRoutes, Response}

class RemoteApi[F[_]: Sync](remotes: RemoteControls[EitherT[F, ControlError, ?]]) extends Common[F] {

  def refineOrBadReq(name: String, device: String, command: String)(
    f: (NonEmptyString, NonEmptyString, NonEmptyString) => F[Response[F]]
  ): F[Response[F]] =
    Semigroupal
      .map3[ValidatedNel[String, ?], NonEmptyString, NonEmptyString, NonEmptyString, F[Response[F]]](
        refineV[NonEmpty](name).toValidatedNel,
        refineV[NonEmpty](device).toValidatedNel,
        refineV[NonEmpty](command).toValidatedNel
      )(f)
      .leftMap(errs => BadRequest(errs.toList.mkString(",")))
      .merge

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / name / device / command / "send" =>
      refineOrBadReq(name, device, command)((n, d, c) => handleControlError(remotes.send(n, d, c)))
    case POST -> Root / name / device / command / "learn" =>
      refineOrBadReq(name, device, command)((n, d, c) => handleControlError(remotes.learn(n, d, c)))
  }
}
