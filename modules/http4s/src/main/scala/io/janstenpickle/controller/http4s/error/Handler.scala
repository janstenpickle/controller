package io.janstenpickle.controller.http4s.error

import cats.effect.Sync
import cats.mtl.ApplicativeHandle
import cats.syntax.apply._
import cats.syntax.functor._
import io.chrisdavenport.log4cats.Logger
import natchez.Trace
import org.http4s.Response
import org.http4s.dsl.Http4sDsl

object Handler {
  def handleControlError[F[_]: Sync](
    result: F[Option[Response[F]]]
  )(implicit trace: Trace[F], logger: Logger[F], ah: ApplicativeHandle[F, ControlError]): F[Option[Response[F]]] = {
    val dsl = Http4sDsl[F]
    import dsl._

    ah.handleWith(result) {
      case err @ ControlError.Missing(message) =>
        trace.put("error" -> true, "reason" -> "missing", "message" -> message) *> logger
          .warn(err)("Missing resource") *> NotFound(message).map(Some(_))
      case err @ ControlError.Internal(message) =>
        trace.put("error" -> true, "reason" -> "internal error", "message" -> message) *> logger
          .error(err)("Internal error") *> InternalServerError(message)
          .map(Some(_))
      case err @ ControlError.InvalidInput(message) =>
        trace.put("error" -> true, "reason" -> "invalid input", "message" -> message) *> logger
          .info(err)("Invalid input") *> BadRequest(message).map(Some(_))
      case err @ ControlError.Combined(_, _) if err.isSevere =>
        trace.put("error" -> true, "reason" -> "internal error", "message" -> err.message) *> logger
          .error(err)("Internal error") *> InternalServerError(err.message)
          .map(Some(_))
      case err @ ControlError.Combined(_, _) =>
        trace.put("error" -> true, "reason" -> "missing", "message" -> err.message) *> logger
          .warn(err)("Missing resource") *> NotFound(err.message).map(Some(_))
    }
  }
}
