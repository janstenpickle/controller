package io.janstenpickle.controller.api

import cats.Monad
import cats.data.EitherT
import cats.syntax.flatMap._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.api.error.ControlError
import org.http4s.dsl.Http4sDsl
import org.http4s.{EntityEncoder, Response}

abstract class Common[F[_]: Monad] extends Http4sDsl[F] {
  def refineOrBadReq(name: String)(f: NonEmptyString => F[Response[F]]): F[Response[F]] =
    refineV[NonEmpty](name).fold(BadRequest(_), f)

  def handleControlError[A](
    result: EitherT[F, ControlError, A]
  )(implicit encoder: EntityEncoder[F, A]): F[Response[F]] =
    result.value.flatMap {
      case Left(ControlError.Missing(message)) => NotFound(message)
      case Left(ControlError.Internal(message)) => InternalServerError(message)
      case Right(a) => Ok(a)
    }
}
