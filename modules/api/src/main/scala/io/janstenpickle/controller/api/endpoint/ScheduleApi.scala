package io.janstenpickle.controller.api.endpoint

import cats.effect.Sync
import cats.syntax.flatMap._
import io.janstenpickle.controller.schedule.Scheduler
import io.janstenpickle.controller.schedule.model.Schedule
import org.http4s.HttpRoutes

class ScheduleApi[F[_]: Sync](scheduler: Scheduler[F]) extends Common[F] {
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
