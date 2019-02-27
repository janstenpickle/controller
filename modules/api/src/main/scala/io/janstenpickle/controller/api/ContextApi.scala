package io.janstenpickle.controller.api

import cats.data.{EitherT, NonEmptyList}
import cats.effect.Sync
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{Command, ContextButtonMapping}
import io.janstenpickle.controller.`macro`.Macro
import org.http4s.{EntityDecoder, EntityEncoder, HttpRoutes}
import io.circe.generic.auto._
import io.circe.refined._
import io.janstenpickle.controller.model
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.api.error.ControlError
import io.janstenpickle.controller.configsource.ActivityConfigSource
import io.janstenpickle.controller.remotecontrol.RemoteControls
import org.http4s.circe.jsonOf
import org.http4s.circe.jsonEncoderOf

class ContextApi[F[_]: Sync](
  activities: Activity[EitherT[F, ControlError, ?]],
  macros: Macro[EitherT[F, ControlError, ?]],
  remotes: RemoteControls[EitherT[F, ControlError, ?]],
  activitySource: ActivityConfigSource[F]
) extends Common[F] {
  implicit val commandsDecoder: EntityDecoder[F, NonEmptyList[Command]] = jsonOf[F, NonEmptyList[Command]]
  implicit val macrosEncoder: EntityEncoder[F, List[NonEmptyString]] = jsonEncoderOf[F, List[NonEmptyString]]

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / name =>
      refineOrBadReq(name) { n =>
        val activity: EitherT[F, ControlError, model.Activity] = activities.getActivity.flatMap {
          case None => EitherT.leftT[F, model.Activity](ControlError.Missing("Activity not currently set"))
          case Some(act) =>
            EitherT
              .liftF[F, ControlError, model.Activities](activitySource.getActivities)
              .map(_.activities.groupBy(_.name).mapValues(_.headOption).get(act).flatten)
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
