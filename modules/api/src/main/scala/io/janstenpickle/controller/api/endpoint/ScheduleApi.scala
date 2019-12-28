package io.janstenpickle.controller.api.endpoint

import java.time.DayOfWeek

import cats.{Applicative, Monad}
import cats.effect.Sync
import cats.syntax.flatMap._
import extruder.circe.CirceSettings
import extruder.circe.instances._
import extruder.core.{Decoder, Encoder, ExtruderErrors}
import extruder.refined._
import io.circe.Json
import io.janstenpickle.controller.schedule.Scheduler
import io.janstenpickle.controller.schedule.model._
import org.http4s.HttpRoutes

import scala.util.Try

class ScheduleApi[F[_]: Sync](scheduler: Scheduler[F]) extends Common[F] {
  import ScheduleApi._

  val routes: HttpRoutes[F] = HttpRoutes.of {
    case GET -> Root => Ok(scheduler.list)
    case GET -> Root / id =>
      scheduler.info(id).flatMap {
        case None => NotFound()
        case Some(schedule) => Ok(schedule)
      }
    case req @ POST -> Root =>
      req.as[Schedule].flatMap(scheduler.create).flatMap {
        case None => InternalServerError("Could not create schedule")
        case Some(id) => Ok(id)
      }
    case req @ PUT -> Root / id =>
      req.as[Schedule].flatMap(scheduler.update(id, _)).flatMap {
        case None => NotFound()
        case Some(_) => Ok()
      }
    case DELETE -> Root / id =>
      scheduler.delete(id).flatMap {
        case None => NotFound()
        case Some(_) => Ok(())
      }
  }

}

object ScheduleApi {
  implicit def dayEncoder[F[_]: Applicative, S <: CirceSettings]: Encoder[F, S, DayOfWeek, Json] =
    Encoder[F, S, Int, Json].contramap(_.getValue)

  implicit def dayDecoder[F[_]: Monad, S <: CirceSettings](
    implicit errors: ExtruderErrors[F]
  ): Decoder[F, S, DayOfWeek, Json] =
    Decoder[F, S, Int, Json].imapResult(v => errors.fromTry(Try(DayOfWeek.of(v))))(_.getValue)
}
