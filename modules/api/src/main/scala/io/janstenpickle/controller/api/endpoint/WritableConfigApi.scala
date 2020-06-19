package io.janstenpickle.controller.api.endpoint

import cats.Semigroupal
import cats.data.ValidatedNel
import cats.effect.{Concurrent, Timer}
import cats.mtl.{ApplicativeHandle, FunctorRaise}
import cats.syntax.either._
import cats.syntax.flatMap._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.refined._
import io.janstenpickle.controller.api.service.WritableConfigService
import io.janstenpickle.controller.http4s.error.ControlError
import io.janstenpickle.controller.model._
import natchez.Trace
import org.http4s._

class WritableConfigApi[F[_]: Timer](service: WritableConfigService[F])(
  implicit F: Concurrent[F],
  fr: FunctorRaise[F, ControlError],
  ah: ApplicativeHandle[F, ControlError],
  trace: Trace[F]
) extends Common[F] {

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
    case req @ PUT -> Root / "activity" / a => Ok(req.as[Activity].flatMap(service.updateActivity(a, _)))
    case req @ POST -> Root / "activity" => Ok(req.as[Activity].flatMap(service.addActivity))
    case DELETE -> Root / "activity" / r / a =>
      refineOrBadReq(r, a) { (room, activity) =>
        Ok(service.deleteActivity(room, activity))
      }

    case req @ POST -> Root / "remote" =>
      Ok(req.as[Remote].flatMap(service.addRemote))
    case req @ PUT -> Root / "remote" / r =>
      refineOrBadReq(r) { remoteName =>
        Ok(req.as[Remote].flatMap(service.updateRemote(remoteName, _)))
      }
    case DELETE -> Root / "remote" / r =>
      refineOrBadReq(r) { remote =>
        Ok(service.deleteRemote(remote))
      }
    case req @ PUT -> Root / "button" / b =>
      refineOrBadReq(b) { button =>
        Ok(req.as[Button].flatMap(service.updateCommonButton(button, _)))
      }
    case req @ POST -> Root / "button" => Ok(req.as[Button].flatMap(service.addCommonButton))
    case DELETE -> Root / "button" / b =>
      refineOrBadReq(b) { button =>
        Ok(service.deleteCommonButton(button))
      }
  }
}
