package io.janstenpickle.controller.context

import cats.{FlatMap, Monad}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model.{ContextButtonMapping, Room}
import io.janstenpickle.controller.model
import io.janstenpickle.controller.remotecontrol.RemoteControls
import io.janstenpickle.controller.switch.Switches
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.applicative._

trait Context[F[_]] {
  def action(room: Room, name: NonEmptyString): F[Unit]
}

object Context {
  def apply[F[_]: Monad](
    activities: Activity[F],
    macros: Macro[F],
    remotes: RemoteControls[F],
    switches: Switches[F],
    activitySource: ConfigSource[F, String, model.Activity]
  )(implicit errors: ContextErrors[F]): Context[F] = new Context[F] {
    override def action(room: Room, name: NonEmptyString): F[Unit] = {
      val activity: F[model.Activity] = activities.getActivity(room).flatMap {
        case None => errors.activityNotSet(room)
        case Some(act) =>
          activitySource.getConfig
            .map(_.values.values.filter(_.room == room).groupBy(_.name).mapValues(_.headOption).get(act).flatten)
            .flatMap {
              case None => errors.activityNotPresent(room, act)
              case Some(a) => a.pure[F]
            }
      }

      activity.flatMap(_.contextButtons.groupBy(_.name).mapValues(_.headOption).get(name).flatten match {
        case Some(ContextButtonMapping.Macro(_, macroName)) => macros.executeMacro(macroName)
        case Some(ContextButtonMapping.Remote(_, remote, commandSource, device, command)) =>
          remotes.send(remote, commandSource, device, command)
        case Some(ContextButtonMapping.ToggleSwitch(_, device, switch)) => switches.toggle(device, switch)
        case None => ().pure[F]
      })
    }
  }
}
