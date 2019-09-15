package io.janstenpickle.controller.api.view

import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{Monad, Parallel, Traverse}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model.Button._
import io.janstenpickle.controller.model._
import io.janstenpickle.controller.store.{ActivityStore, MacroStore}
import io.janstenpickle.controller.switch.Switches
import io.janstenpickle.controller.switch.model.SwitchKey
import natchez.Trace

class ConfigView[F[_]: Parallel](
  activity: ConfigSource[F, Activities],
  button: ConfigSource[F, Buttons],
  remote: ConfigSource[F, Remotes],
  macros: MacroStore[F],
  activityStore: ActivityStore[F],
  switches: Switches[F]
)(implicit F: Monad[F], trace: Trace[F]) {
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

  private def addSwitchState[G[_]: Traverse](buttons: G[Button]): F[G[Button]] = buttons.parTraverse {
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
      .parTraverse { activity =>
        activityStore
          .loadActivity(activity.room)
          .map(
            _.fold(activity)(active => if (activity.name == active) activity.copy(isActive = Some(true)) else activity)
          )
      }
      .map { acts =>
        activities.copy(activities = acts)
      }

  def getActivities: F[Activities] = trace.span("getActivities") { activity.getConfig.flatMap(addActiveActivity) }

  def getRemotes: F[Remotes] = trace.span("getRemotes") {
    remote.getConfig.flatMap { remotes =>
      remotes.remotes
        .parTraverse { remote =>
          addSwitchState(remote.buttons).map(b => remote.copy(buttons = b))
        }
        .map(rs => remotes.copy(remotes = rs))
    }
  }

  def getCommonButtons: F[Buttons] = trace.span("getCommonButtons") {
    button.getConfig.flatMap { buttons =>
      addSwitchState(buttons.buttons).map { bs =>
        buttons.copy(buttons = bs)
      }
    }
  }

  implicit val ord: Ordering[Int] = new Ordering[Int] {
    override def compare(x: Int, y: Int): Int = if (x < y) 1 else if (x > y) -1 else 0
  }

  def getRooms: F[Rooms] = trace.span("getRooms") {
    List(
      trace.span("getRemotes") { remote.getConfig.map(r => r.remotes.flatMap(_.rooms) -> r.errors) },
      trace.span("getActivities") { activity.getConfig.map(a => a.activities.map(_.room) -> a.errors) },
      trace.span("getCommonButtons") { button.getConfig.map(b => b.buttons.flatMap(_.room) -> b.errors) }
    ).parSequence.map { data =>
      val (remotes, errors) = data.unzip

      Rooms(
        remotes.flatten
          .groupBy(identity)
          .mapValues(_.size)
          .toList
          .sortBy(_._2)
          .map(_._1),
        errors.flatten
      )
    }
  }
}
