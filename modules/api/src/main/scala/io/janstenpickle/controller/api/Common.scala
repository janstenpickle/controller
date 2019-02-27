package io.janstenpickle.controller.api

import cats.{Id, Monad}
import cats.data.EitherT
import cats.effect.Sync
import cats.syntax.flatMap._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import extruder.circe.CirceSettings
import extruder.circe._
import extruder.core.{DecoderT, EncoderT, ValidationErrorsToThrowable}
import extruder.data.Validation
import io.circe.Json
import io.janstenpickle.controller.api.error.ControlError
import org.http4s.dsl.Http4sDsl
import org.http4s.{DecodeResult, EntityDecoder, EntityEncoder, InvalidMessageBodyFailure, Response}
import org.http4s.circe._

abstract class Common[F[_]: Sync] extends Http4sDsl[F] {
  def extruderDecoder[A](implicit decoder: DecoderT[Validation, CirceSettings, A, Json]): EntityDecoder[F, A] =
    jsonDecoder[F].flatMapR { json =>
      decode[A](json).fold(
        failures =>
          DecodeResult.failure[F, A](
            InvalidMessageBodyFailure(
              s"Could not decode JSON: $json",
              Some(ValidationErrorsToThrowable.defaultValidationErrorsThrowable.convertErrors(failures))
            )
        ),
        DecodeResult.success[F, A]
      )
    }

  def extruderEncoder[A](implicit encoder: EncoderT[Id, CirceSettings, A, Json]): EntityEncoder[F, A] =
    jsonEncoder.contramap[A](encode(_))

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
