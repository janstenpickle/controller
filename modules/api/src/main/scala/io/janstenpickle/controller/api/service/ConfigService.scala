package io.janstenpickle.controller.api.service

import cats.data.NonEmptyList
import cats.effect.Sync
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{MonadError, Parallel, Traverse}
import eu.timepit.refined.types.string.NonEmptyString
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.api.validation.ConfigValidation
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.model.Button.{MacroIcon, MacroLabel, SwitchIcon, SwitchLabel}
import io.janstenpickle.controller.model._
import io.janstenpickle.controller.store.{ActivityStore, MacroStore}
import io.janstenpickle.controller.switch.Switches
import io.janstenpickle.controller.switch.model.SwitchKey
import natchez.Trace

import scala.collection.immutable.ListMap

class ConfigService[F[_]: Parallel] private (
  activity: WritableConfigSource[F, String, Activity],
  button: WritableConfigSource[F, String, Button],
  remote: WritableConfigSource[F, NonEmptyString, Remote],
  macros: MacroStore[F],
  activityStore: ActivityStore[F],
  switches: Switches[F],
  validation: ConfigValidation[F],
  logger: Logger[F]
)(implicit F: MonadError[F, Throwable], errors: ConfigServiceErrors[F], trace: Trace[F]) {
  private def doIfPresent[A](device: NonEmptyString, name: NonEmptyString, a: A, op: State => A): F[A] =
    switches.list.flatMap { switchList =>
      if (switchList.contains(SwitchKey(device, name))) F.handleErrorWith(switches.getState(device, name).map(op)) {
        err =>
          logger.warn(err)(s"Could not update switch state for '$device' '$name'").as(a)
      } else F.pure(a)
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

  private def addActiveActivity(activities: ConfigResult[String, Activity]): F[ConfigResult[String, Activity]] =
    activities.values.toList
      .parTraverse {
        case (k, activity) =>
          activityStore
            .loadActivity(activity.room)
            .map(
              _.fold(activity)(
                active =>
                  if (activity.name == active) activity.copy(isActive = Some(true))
                  else activity
              )
            )
            .map(k -> _)
      }
      .map { acts =>
        activities.copy(values = ListMap(acts.sortBy(_._2.order): _*))
      }

  def getActivities: F[ConfigResult[String, Activity]] = trace.span("getActivities") {
    activity.getConfig.flatMap(addActiveActivity)
  }

  def addActivity(a: Activity): F[ConfigResult[String, Activity]] = trace.span("addActivity") {
    val activityKey = s"${a.room}-${a.name}"

    activity.getValue(activityKey).flatMap {
      case Some(_) => errors.activityAlreadyExists[ConfigResult[String, Activity]](a.room, a.name)
      case None => updateActivity(activityKey, a)
    }
  }

  def updateActivity(activityName: String, a: Activity): F[ConfigResult[String, Activity]] =
    trace.span("updateActivity") {
      validation
        .validateActivity(a)
        .flatMap(NonEmptyList.fromList(_) match {
          case None => activity.upsert(activityName, a)
          case Some(errs) => errors.configValidationFailed[ConfigResult[String, Activity]](errs)
        })
    }

  def deleteActivity(a: NonEmptyString): F[ConfigResult[String, Activity]] = trace.span("deleteActivity") {
    activity.getConfig.flatMap { activities =>
      if (activities.values.values.map(_.name).toSet.contains(a)) remote.getConfig.flatMap { remotes =>
        val rs = remotes.values.values.collect {
          case r if r.activities.contains(a) => r.name
        }
        if (rs.nonEmpty) errors.activityInUse(a, rs.toList)
        else activity.deleteItem(a.value)
      } else errors.activityMissing[ConfigResult[String, Activity]](a)

    }
  }

  def getRemotes: F[ConfigResult[NonEmptyString, Remote]] = trace.span("getRemotes") {
    remote.getConfig.flatMap { remotes =>
      remotes.values.toList
        .parTraverse {
          case (k, remote) =>
            addSwitchState(remote.buttons).map(b => k -> remote.copy(buttons = b))
        }
        .map(rs => remotes.copy(values = ListMap(rs.sortBy(_._2.order): _*)))
    }
  }

  def addRemote(r: Remote): F[ConfigResult[NonEmptyString, Remote]] = trace.span("addRemote") {
    remote.getValue(r.name).flatMap {
      case Some(_) => errors.remoteAlreadyExists(r.name)
      case None => updateRemote(r.name, r)
    }
  }

  def updateRemote(remoteName: NonEmptyString, r: Remote): F[ConfigResult[NonEmptyString, Remote]] =
    trace.span("updateRemote") {
      validation
        .validateRemote(r)
        .flatMap(NonEmptyList.fromList(_) match {
          case None => remote.upsert(remoteName, r)
          case Some(errs) => errors.configValidationFailed[ConfigResult[NonEmptyString, Remote]](errs)
        })
    }

  def deleteRemote(r: NonEmptyString): F[ConfigResult[NonEmptyString, Remote]] = trace.span("deleteRemote") {
    remote.getConfig.flatMap { remotes =>
      if (remotes.values.keySet.contains(r)) remote.deleteItem(r)
      else errors.remoteMissing[ConfigResult[NonEmptyString, Remote]](r)
    }
  }

  def getCommonButtons: F[ConfigResult[String, Button]] = trace.span("getCommonButtons") {
    button.getConfig.flatMap { buttons =>
      addSwitchState(buttons.values.values.toList).map { bs =>
        buttons.copy(
          values = ListMap(
            bs.map { b =>
                s"${b.room.getOrElse("all")}-${b.name}" -> b
              }
              .sortBy(_._2.order): _*
          )
        )
      }
    }
  }

  def updateCommonButton(buttonName: NonEmptyString, b: Button): F[ConfigResult[String, Button]] =
    trace.span("updateCommonButton") {
      validation
        .validateButton(b)
        .flatMap(NonEmptyList.fromList(_) match {
          case None => button.upsert(s"${b.room.getOrElse("all")}-${b.name}", b)
          case Some(errs) => errors.configValidationFailed[ConfigResult[String, Button]](errs)
        })
    }

  def addCommonButton(b: Button): F[ConfigResult[String, Button]] = trace.span("addCommonButton") {
    button.getValue(s"${b.room.getOrElse("all")}-${b.name}").flatMap {
      case Some(_) => errors.buttonAlreadyExists(b.name)
      case None => updateCommonButton(b.name, b)
    }
  }

  def deleteCommonButton(b: String): F[ConfigResult[String, Button]] =
    trace.span("deleteCommonButton") {
      button.getConfig.flatMap { buttons =>
        if (buttons.values.keySet.contains(b)) button.deleteItem(b)
        else errors.buttonMissing[ConfigResult[String, Button]](b)
      }
    }

  implicit val ord: Ordering[Int] = new Ordering[Int] {
    override def compare(x: Int, y: Int): Int = if (x < y) 1 else if (x > y) -1 else 0
  }

  def getRooms: F[Rooms] = trace.span("getRooms") {
    List(
      trace.span("getRemotes") { remote.getConfig.map(r => r.values.values.flatMap(_.rooms) -> r.errors) },
      trace.span("getActivities") { activity.getConfig.map(a => a.values.values.map(_.room) -> a.errors) },
      trace.span("getCommonButtons") { button.getConfig.map(b => b.values.values.flatMap(_.room) -> b.errors) }
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

object ConfigService {
  def apply[F[_]: Sync: Parallel: Trace: ConfigServiceErrors](
    activity: WritableConfigSource[F, String, Activity],
    button: WritableConfigSource[F, String, Button],
    remote: WritableConfigSource[F, NonEmptyString, Remote],
    macros: MacroStore[F],
    activityStore: ActivityStore[F],
    switches: Switches[F],
    validation: ConfigValidation[F]
  ): F[ConfigService[F]] =
    Slf4jLogger
      .fromClass[F](this.getClass)
      .map(new ConfigService[F](activity, button, remote, macros, activityStore, switches, validation, _))
}
