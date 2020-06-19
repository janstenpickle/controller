package io.janstenpickle.controller.api.endpoint

import cats.Id
import cats.effect.Sync
import cats.syntax.flatMap._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import org.http4s.Response
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.{DecodeResult, EntityDecoder, EntityEncoder, InvalidMessageBodyFailure, Response}

abstract class Common[F[_]: Sync] extends Http4sDsl[F] with CirceEntityDecoder with CirceEntityEncoder {

//  implicit def extruderDecoder[A](implicit decoder: Decoder[Validation, CirceSettings, A, Json]): EntityDecoder[F, A] =
//    jsonDecoder[F].flatMapR { json =>
//      decode[A](json).fold(
//        failures =>
//          DecodeResult.failure[F, A](
//            InvalidMessageBodyFailure(
//              s"Could not decode JSON: $json",
//              None
//              //Some(ValidationErrorsToThrowable.defaultValidationErrorsThrowable.convertErrors(failures))
//            )
//        ),
//        DecodeResult.success[F, A]
//      )
//    }
//
//  implicit def extruderEncoder[A](implicit encoder: Encoder[Id, CirceSettings, A, Json]): EntityEncoder[F, A] =
//    jsonEncoder.contramap[A](encode(_))

  def refineOrBadReq(name: String)(f: NonEmptyString => F[Response[F]]): F[Response[F]] =
    refineV[NonEmpty](name).fold(BadRequest(_), f)
}
