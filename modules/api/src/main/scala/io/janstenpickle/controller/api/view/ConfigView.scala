package io.janstenpickle.controller.api.view

import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Monad, Traverse}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.{ActivityConfigSource, ButtonConfigSource, RemoteConfigSource}
import io.janstenpickle.controller.model.Button._
import io.janstenpickle.controller.model._
import io.janstenpickle.controller.store.{ActivityStore, MacroStore}
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.Switches

class ConfigView[F[_]](
  activity: ActivityConfigSource[F],
  button: ButtonConfigSource[F],
  remote: RemoteConfigSource[F],
  macros: MacroStore[F],
  activityStore: ActivityStore[F],
  switches: Switches[F]
)(implicit F: Monad[F]) {
  private def doIfPresent[A](device: NonEmptyString, name: NonEmptyString, a: A, op: State => A): F[A] =
    switches.list.flatMap { switchList =>
      if (switchList.contains(SwitchKey(device, name))) switches.getState(device, name).map(op) else F.pure(a)
    }

  private def macroSwitchState(device: NonEmptyString, name: NonEmptyString): F[Option[Boolean]] =
    doIfPresent[Option[Boolean]](device, name, None, s => Some(s.isOn))

  /*
   Go through all the macro commands to see if one is a switch
   Use the state of that switch if only one command is a switch, otherwise return nothing
   */
  private def macroSwitchStateIfPresent(macroName: NonEmptyString): F[Option[Boolean]] =
    macros.loadMacro(macroName).flatMap {
      case None => F.pure(None)
      case Some(commands) =>
        commands
          .foldLeft(F.pure(List.empty[Boolean])) {
            case (acc, Command.ToggleSwitch(device, n)) =>
              acc.flatMap(states => macroSwitchState(device, n).map(states ++ _))
            case (acc, Command.SwitchOn(device, n)) =>
              acc.flatMap(states => macroSwitchState(device, n).map(states ++ _))
            case (acc, Command.SwitchOff(device, n)) =>
              acc.flatMap(states => macroSwitchState(device, n).map(states ++ _))
            case (acc, _) => acc
          }
          .map {
            case s :: Nil => Some(s)
            case _ => None
          }
    }

  private def addSwitchState[G[_]: Traverse](buttons: G[Button]): F[G[Button]] = buttons.traverse {
    case button: SwitchIcon => doIfPresent(button.device, button.name, button, state => button.copy(isOn = state.isOn))
    case button: SwitchLabel => doIfPresent(button.device, button.name, button, state => button.copy(isOn = state.isOn))
    case macroButton: MacroIcon =>
      macroSwitchStateIfPresent(macroButton.name).map(isOn => macroButton.copy(isOn = isOn))
    case macroButton: MacroLabel =>
      macroSwitchStateIfPresent(macroButton.name).map(isOn => macroButton.copy(isOn = isOn))
    case button: Any => F.pure(button)
  }

  private def addActiveActivity(activities: Activities): F[Activities] =
    activities.activities
      .traverse { activity =>
        activityStore
          .loadActivity(activity.room)
          .map(
            _.fold(activity)(active => if (activity.name == active) activity.copy(isActive = Some(true)) else activity)
          )
      }
      .map { acts =>
        activities.copy(activities = acts)
      }

  def getActivities: F[Activities] = activity.getActivities.flatMap(addActiveActivity)

  def getRemotes: F[Remotes] = remote.getRemotes.flatMap { remotes =>
    remotes.remotes
      .traverse { remote =>
        addSwitchState(remote.buttons).map(b => remote.copy(buttons = b))
      }
      .map(rs => remotes.copy(remotes = rs))
  }

  def getCommonButtons: F[Buttons] = button.getCommonButtons.flatMap { buttons =>
    addSwitchState(buttons.buttons).map { bs =>
      buttons.copy(buttons = bs)
    }
  }

  implicit val ord: Ordering[Int] = new Ordering[Int] {
    override def compare(x: Int, y: Int): Int = if (x < y) 1 else if (x > y) -1 else 0
  }

  def getRooms: F[Rooms] =
    for {
      r <- remote.getRemotes
      a <- activity.getActivities
      b <- button.getCommonButtons
    } yield
      Rooms(
        (r.remotes.flatMap(_.rooms) ++ a.activities.map(_.room) ++ b.buttons.flatMap(_.room))
          .groupBy(identity)
          .mapValues(_.size)
          .toList
          .sortBy(_._2)
          .map(_._1),
        r.errors ++ a.errors ++ b.errors
      )
}
