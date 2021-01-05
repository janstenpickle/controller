package io.janstenpickle.controller.api.endpoint

import cats.Semigroupal
import cats.data.ValidatedNel
import cats.effect.Sync
import cats.mtl.ApplicativeHandle
import cats.syntax.either._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.refined._
import io.janstenpickle.controller.context.Context
import io.janstenpickle.controller.http4s.error.ControlError
import org.http4s.{HttpRoutes, Response}

class ContextApi[F[_]: Sync](context: Context[F])(implicit ah: ApplicativeHandle[F, ControlError]) extends Common[F] {
  def refineOrBadReq(room: String, name: String)(
    f: (NonEmptyString, NonEmptyString) => F[Response[F]]
  ): F[Response[F]] =
    Semigroupal
      .map2[ValidatedNel[String, *], NonEmptyString, NonEmptyString, F[Response[F]]](
        refineV[NonEmpty](room).toValidatedNel,
        refineV[NonEmpty](name).toValidatedNel
      )(f)
      .leftMap(errs => BadRequest(errs.toList.mkString(",")))
      .merge

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / room / name =>
      refineOrBadReq(room, name) { (r, n) =>
        Ok(context.action(r, n))
      }
  }
}
