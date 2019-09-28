package io.janstenpickle.controller.api.endpoint

import cats.Semigroupal
import cats.data.ValidatedNel
import cats.effect.Sync
import cats.mtl.{ApplicativeHandle, FunctorRaise}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.types.string.NonEmptyString
import extruder.circe.instances._
import extruder.refined._
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model
import io.janstenpickle.controller.model.{Activity => ActivityModel, Command, ContextButtonMapping}
import io.janstenpickle.controller.remotecontrol.RemoteControls
import org.http4s.{HttpRoutes, Response}

class ContextApi[F[_]: Sync](
  activities: Activity[F],
  macros: Macro[F],
  remotes: RemoteControls[F],
  activitySource: ConfigSource[F, String, ActivityModel]
)(implicit fr: FunctorRaise[F, ControlError], ah: ApplicativeHandle[F, ControlError])
    extends Common[F] {
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
    case POST -> Root / room / name =>
      refineOrBadReq(room, name) { (r, n) =>
        val activity: F[model.Activity] = activities.getActivity(r).flatMap {
          case None => fr.raise(ControlError.Missing("Activity not currently set"))
          case Some(act) =>
            activitySource.getConfig
              .map(_.values.values.filter(_.room == r).groupBy(_.name).mapValues(_.headOption).get(act).flatten)
              .flatMap {
                case None =>
                  fr.raise(ControlError.Missing(s"Current activity '$act' is not present in configuration"))
                case Some(a) => a.pure[F]
              }
        }

        Ok(activity.flatMap(_.contextButtons.groupBy(_.name).mapValues(_.headOption).get(n).flatten match {
          case Some(ContextButtonMapping.Macro(_, macroName)) => macros.executeMacro(macroName)
          case Some(ContextButtonMapping.Remote(_, remote, device, command)) => remotes.send(remote, device, command)
          case None =>
            fr.raise[Unit](ControlError.Missing(s"Could not find context button '$n' in current activity"))
        }))
      }
  }
}
