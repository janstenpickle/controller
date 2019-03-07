package io.janstenpickle.controller.api

import cats.Semigroupal
import cats.data.{EitherT, NonEmptyList, ValidatedNel}
import cats.effect.Sync
import cats.syntax.either._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import extruder.circe.instances._
import extruder.refined._
import io.janstenpickle.controller.model.{Command, ContextButtonMapping}
import io.janstenpickle.controller.`macro`.Macro
import org.http4s.{EntityDecoder, HttpRoutes, Response}
import io.janstenpickle.controller.model
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.configsource.ActivityConfigSource
import io.janstenpickle.controller.remotecontrol.RemoteControls

class ContextApi[F[_]: Sync](
  activities: Activity[EitherT[F, ControlError, ?]],
  macros: Macro[EitherT[F, ControlError, ?]],
  remotes: RemoteControls[EitherT[F, ControlError, ?]],
  activitySource: ActivityConfigSource[EitherT[F, ControlError, ?]]
) extends Common[F] {
  implicit val commandsDecoder: EntityDecoder[F, NonEmptyList[Command]] = extruderDecoder[NonEmptyList[Command]]

  def refineOrBadReq(room: String, name: String)(
    f: (NonEmptyString, NonEmptyString) => F[Response[F]]
  ): F[Response[F]] =
    Semigroupal
      .map2[ValidatedNel[String, ?], NonEmptyString, NonEmptyString, F[Response[F]]](
        refineV[NonEmpty](room).toValidatedNel,
        refineV[NonEmpty](name).toValidatedNel
      )(f)
      .leftMap(errs => BadRequest(errs.toList.mkString(",")))
      .merge

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / room / name =>
      refineOrBadReq(room, name) { (r, n) =>
        val activity: EitherT[F, ControlError, model.Activity] = activities.getActivity(r).flatMap {
          case None => EitherT.leftT[F, model.Activity](ControlError.Missing("Activity not currently set"))
          case Some(act) =>
            activitySource.getActivities
              .map(_.activities.filter(_.room == r).groupBy(_.name).mapValues(_.headOption).get(act).flatten)
              .flatMap {
                case None =>
                  EitherT.leftT[F, model.Activity](
                    ControlError.Missing(s"Current activity '$act' is present in configuration")
                  )
                case Some(a) => EitherT.pure[F, ControlError](a)
              }
        }

        handleControlError(
          activity.flatMap(_.contextButtons.groupBy(_.name).mapValues(_.headOption).get(n).flatten match {
            case Some(ContextButtonMapping.Macro(_, macroName)) => macros.executeMacro(macroName)
            case Some(ContextButtonMapping.Remote(_, remote, device, command)) => remotes.send(remote, device, command)
            case None =>
              EitherT.leftT[F, Unit](ControlError.Missing(s"Could not find context button '$n' in current activity"))
          })
        )
      }
  }
}
