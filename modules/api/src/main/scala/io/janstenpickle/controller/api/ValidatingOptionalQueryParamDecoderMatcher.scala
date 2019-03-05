package io.janstenpickle.controller.api

import cats.data.Validated.Valid
import cats.data.ValidatedNel
import org.http4s.{ParseFailure, QueryParamDecoder, QueryParameterValue}

abstract class ValidatingOptionalQueryParamDecoderMatcher[T: QueryParamDecoder](name: String) {
  def unapply(params: Map[String, Seq[String]]): Option[ValidatedNel[ParseFailure, Option[T]]] =
    Some(
      params
        .get(name)
        .flatMap(_.headOption)
        .fold[ValidatedNel[ParseFailure, Option[T]]](Valid(None))(
          s =>
            QueryParamDecoder[T]
              .decode(QueryParameterValue(s))
              .leftMap(_.map(msg => msg.copy(sanitized = s"Failed to parse query parameter '$name': ${msg.sanitized}")))
              .map(Some(_))
        )
    )
}
