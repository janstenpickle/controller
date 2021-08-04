package io.janstenpickle.controller.http4s.error

import cats.effect.Sync
import cats.mtl.ApplicativeHandle
import cats.syntax.apply._
import cats.syntax.functor._
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.SpanStatus
import org.http4s.Response
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.Logger

object Handler {
  def handleControlError[F[_]: Sync](
    result: F[Option[Response[F]]]
  )(implicit trace: Trace[F], logger: Logger[F], ah: ApplicativeHandle[F, ControlError]): F[Option[Response[F]]] = {
    val dsl = Http4sDsl[F]
    import dsl._

    ah.handleWith(result) {
      case err @ ControlError.Missing(message) =>
        trace.put("message", message) *> trace.setStatus(SpanStatus.NotFound) *> logger
          .warn(err)("Missing resource") *> NotFound(message).map(Some(_))
      case err @ ControlError.Internal(message) =>
        trace.setStatus(SpanStatus.Internal(message)) *> logger
          .error(err)("Internal error") *> InternalServerError(message)
          .map(Some(_))
      case err @ ControlError.InvalidInput(message) =>
        trace.put("message", message) *> trace.setStatus(SpanStatus.InvalidArgument) *> logger
          .info(err)("Invalid input") *> BadRequest(message).map(Some(_))
      case err @ ControlError.Combined(_, _) if err.isSevere =>
        trace.setStatus(SpanStatus.Internal(err.message)) *> logger
          .error(err)("Internal error") *> InternalServerError(err.message)
          .map(Some(_))
      case err @ ControlError.Combined(_, _) =>
        trace.put("message", err.message) *> trace.setStatus(SpanStatus.Unknown) *> logger
          .warn(err)("Missing resource") *> NotFound(err.message).map(Some(_))
    }
  }
}
