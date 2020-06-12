package io.janstenpickle.controller.api.service

import cats.data.{NonEmptyList, OptionT}
import cats.effect.Sync
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{MonadError, Parallel, Traverse}
import eu.timepit.refined.types.string.NonEmptyString
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.`macro`.store.MacroStore
import io.janstenpickle.controller.activity.store.ActivityStore
import io.janstenpickle.controller.api.validation.ConfigValidation
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource, WritableConfigSource}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.Button.{
  ContextIcon,
  ContextLabel,
  MacroIcon,
  MacroLabel,
  SwitchIcon,
  SwitchLabel
}
import io.janstenpickle.controller.model.event.ConfigEvent
import io.janstenpickle.controller.model.{SwitchKey, _}
import io.janstenpickle.controller.switch.Switches
import natchez.Trace

import scala.collection.immutable.ListMap

abstract class ConfigService[F[_]: Parallel](
  activity: ConfigSource[F, String, Activity],
  button: ConfigSource[F, String, Button],
  remote: ConfigSource[F, NonEmptyString, Remote],
  loadMacro: NonEmptyString => F[Option[NonEmptyList[Command]]],
  loadActivity: Room => F[Option[NonEmptyString]],
  switches: Switches[F],
  logger: Logger[F]
)(implicit F: MonadError[F, Throwable], errors: ConfigServiceErrors[F], trace: Trace[F]) {

  protected def doIfPresent[A](device: NonEmptyString, name: NonEmptyString, default: A, op: State => A): F[A] =
    switches.list.flatMap { switchSet =>
      if (switchSet.contains(SwitchKey(device, name))) F.handleErrorWith(switches.getState(device, name).map(op)) {
        err =>
          logger.warn(err)(s"Could not update switch state for '$device' '$name'").as(default)
      } else F.pure(default)
    }

  protected def macroSwitchState(device: NonEmptyString, name: NonEmptyString): F[Option[Boolean]] =
    doIfPresent[Option[Boolean]](device, name, None, s => Some(s.isOn))

  /*
   Go through all the macro commands to see if one is a switch
   Use the state of that switch if only one command is a switch, otherwise return nothing
   */
  protected def macroSwitchStateIfPresent(macroName: NonEmptyString): F[Option[Boolean]] =
    loadMacro(macroName).flatMap {
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

  protected def contextSwitchStateIfPresent(name: NonEmptyString, room: Option[Room]): F[Option[Boolean]] =
    (for {
      r <- OptionT.fromOption[F](room)
      a <- OptionT(loadActivity(r))
      act <- OptionT(activity.getValue(a.value))
      button <- OptionT.fromOption[F](act.contextButtons.find(_.name == name))
      state <- button match {
        case ContextButtonMapping.ToggleSwitch(_, device, switch) => OptionT(macroSwitchState(device, switch))
        case _ => OptionT.none[F, Boolean]
      }
    } yield state).value

  protected def addSwitchState[G[_]: Traverse](buttons: G[Button]): F[G[Button]] = buttons.parTraverse {
    case button: SwitchIcon => doIfPresent(button.device, button.name, button, state => button.copy(isOn = state.isOn))
    case button: SwitchLabel => doIfPresent(button.device, button.name, button, state => button.copy(isOn = state.isOn))
    case macroButton: MacroIcon =>
      macroSwitchStateIfPresent(macroButton.name).map(isOn => macroButton.copy(isOn = isOn))
    case macroButton: MacroLabel =>
      macroSwitchStateIfPresent(macroButton.name).map(isOn => macroButton.copy(isOn = isOn))
    case contextButton: ContextIcon =>
      contextSwitchStateIfPresent(contextButton.name, contextButton.room).map(isOn => contextButton.copy(isOn = isOn))
    case contextButton: ContextLabel =>
      contextSwitchStateIfPresent(contextButton.name, contextButton.room).map(isOn => contextButton.copy(isOn = isOn))
    case button: Any => F.pure(button)
  }

  protected def addActiveActivity(activities: ConfigResult[String, Activity]): F[ConfigResult[String, Activity]] =
    activities.values.toList
      .parTraverse {
        case (k, activity) =>
          loadActivity(activity.room)
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
        activities.copy(values = ListMap.from(acts.sortBy(_._2.order).reverse))
      }

  def getActivities: F[ConfigResult[String, Activity]] = trace.span("get.activities") {
    activity.getConfig.flatMap(addActiveActivity)
  }

  def getRemotes: F[ConfigResult[NonEmptyString, Remote]] = trace.span("get.remotes") {
    remote.getConfig.flatMap { remotes =>
      remotes.values.toList
        .parTraverse {
          case (k, remote) =>
            addSwitchState(remote.buttons).map(b => k -> remote.copy(buttons = b))
        }
        .map(rs => remotes.copy(values = ListMap.from(rs.sortBy(_._2.order).reverse)))
    }
  }

  def getCommonButtons: F[ConfigResult[String, Button]] = trace.span("get.commonButtons") {
    button.getConfig.flatMap { buttons =>
      addSwitchState(buttons.values.values.toList).map { bs =>
        buttons.copy(
          values = ListMap.from(
            bs.map { b =>
                s"${b.room.getOrElse("all")}-${b.name}" -> b
              }
              .sortBy(_._2.order)
              .reverse
          )
        )
      }
    }
  }

  implicit val ord: Ordering[Int] = new Ordering[Int] {
    override def compare(x: Int, y: Int): Int = if (x < y) 1 else if (x > y) -1 else 0
  }

  def getRooms: F[Rooms] = trace.span("get.rooms") {
    List(
      trace.span("get.remotes") { remote.getConfig.map(r => r.values.values.flatMap(_.rooms) -> r.errors) },
      trace.span("get.activities") { activity.getConfig.map(a => a.values.values.map(_.room) -> a.errors) },
      trace.span("get.common.buttons") { button.getConfig.map(b => b.values.values.flatMap(_.room) -> b.errors) }
    ).parSequence.map { data =>
      val (remotes, errors) = data.unzip

      Rooms(
        remotes.flatten
          .groupBy(identity)
          .view
          .mapValues(_.size)
          .toList
          .sortBy(_._2)
          .map(_._1),
        errors.flatten
      )
    }
  }

}

class WritableConfigService[F[_]: Parallel](
  activity: WritableConfigSource[F, String, Activity],
  button: WritableConfigSource[F, String, Button],
  remote: WritableConfigSource[F, NonEmptyString, Remote],
  macros: MacroStore[F],
  activityStore: ActivityStore[F],
  switches: Switches[F],
  validation: ConfigValidation[F],
  configEventPublisher: EventPublisher[F, ConfigEvent],
  logger: Logger[F]
)(implicit F: MonadError[F, Throwable], errors: ConfigServiceErrors[F], trace: Trace[F])
    extends ConfigService[F](activity, button, remote, macros.loadMacro, activityStore.loadActivity, switches, logger) {
  import ConfigService._

  def addActivity(a: Activity): F[ConfigResult[String, Activity]] = trace.span("add.activity") {
    val activityKey = s"${a.room}-${a.name}"

    activity.getValue(activityKey).flatMap {
      case Some(_) => errors.activityAlreadyExists[ConfigResult[String, Activity]](a.room, a.name)
      case None => updateActivity(activityKey, a)
    }
  }

  def updateActivity(activityName: String, a: Activity): F[ConfigResult[String, Activity]] =
    trace.span("update.activity") {
      validation
        .validateActivity(a)
        .flatMap(NonEmptyList.fromList(_) match {
          case None =>
            activity
              .upsert(activityName, a)
              .flatTap(
                _ =>
                  configEventPublisher
                    .publish1(ConfigEvent.ActivityAddedEvent(a, eventSource))
              )
          case Some(errs) => errors.configValidationFailed[ConfigResult[String, Activity]](errs)
        })
    }

  def deleteActivity(room: Room, a: NonEmptyString): F[ConfigResult[String, Activity]] = trace.span("delete.activity") {
    activity.getConfig.flatMap { activities =>
      if (activities.values.values.map(_.name).toSet.contains(a)) remote.getConfig.flatMap { remotes =>
        val rs = remotes.values.values.collect {
          case r if r.activities.contains(a) => r.name
        }
        if (rs.nonEmpty) errors.activityInUse(a, rs.toList)
        else
          activity
            .deleteItem(a.value)
            .flatTap(_ => configEventPublisher.publish1(ConfigEvent.ActivityRemovedEvent(room, a, eventSource)))
      } else errors.activityMissing[ConfigResult[String, Activity]](a)

    }
  }

  def addRemote(r: Remote): F[ConfigResult[NonEmptyString, Remote]] = trace.span("add.remote") {
    remote.getValue(r.name).flatMap {
      case Some(_) => errors.remoteAlreadyExists(r.name)
      case None => updateRemote(r.name, r)
    }
  }

  def updateRemote(remoteName: NonEmptyString, r: Remote): F[ConfigResult[NonEmptyString, Remote]] =
    trace.span("update.remote") {
      validation
        .validateRemote(r)
        .flatMap(NonEmptyList.fromList(_) match {
          case None =>
            remote
              .upsert(remoteName, r)
              .flatTap(_ => configEventPublisher.publish1(ConfigEvent.RemoteAddedEvent(r, eventSource)))
          case Some(errs) => errors.configValidationFailed[ConfigResult[NonEmptyString, Remote]](errs)
        })
    }

  def deleteRemote(r: NonEmptyString): F[ConfigResult[NonEmptyString, Remote]] = trace.span("delete.remote") {
    remote.getConfig.flatMap { remotes =>
      if (remotes.values.keySet.contains(r))
        remote.deleteItem(r).flatTap(_ => configEventPublisher.publish1(ConfigEvent.RemoteRemovedEvent(r, eventSource)))
      else errors.remoteMissing[ConfigResult[NonEmptyString, Remote]](r)
    }
  }

  def updateCommonButton(buttonName: NonEmptyString, b: Button): F[ConfigResult[String, Button]] =
    trace.span("updateCommonButton") {
      validation
        .validateButton(b)
        .flatMap(NonEmptyList.fromList(_) match {
          case None =>
            button
              .upsert(s"${b.room.getOrElse("all")}-${b.name}", b)
              .flatTap(
                _ =>
                  configEventPublisher
                    .publish1(ConfigEvent.ButtonAddedEvent(b, eventSource))
              )
          case Some(errs) => errors.configValidationFailed[ConfigResult[String, Button]](errs)
        })
    }

  def addCommonButton(b: Button): F[ConfigResult[String, Button]] = trace.span("addCommonButton") {
    button.getValue(s"${b.room.getOrElse("all")}-${b.name}").flatMap {
      case Some(_) => errors.buttonAlreadyExists(b.name)
      case None =>
        updateCommonButton(b.name, b)
    }
  }

  def deleteCommonButton(b: NonEmptyString): F[ConfigResult[String, Button]] =
    trace.span("delete.common.button") {
      button.getConfig.flatMap { buttons =>
        if (buttons.values.keySet.contains(b.value)) button.deleteItem(b.value)
        else
          errors
            .buttonMissing[ConfigResult[String, Button]](b.value)
            .flatTap(
              _ =>
                configEventPublisher
                  .publish1(ConfigEvent.ButtonRemovedEvent(b, None, eventSource))
            )
      }
    }

}

object ConfigService {
  final val eventSource: String = "configService"

  def apply[F[_]: Sync: Parallel: Trace: ConfigServiceErrors](
    activity: ConfigSource[F, String, Activity],
    button: ConfigSource[F, String, Button],
    remote: ConfigSource[F, NonEmptyString, Remote],
    macros: ConfigSource[F, NonEmptyString, NonEmptyList[Command]],
    currentActivity: ConfigSource[F, Room, NonEmptyString],
    switches: Switches[F],
  ): F[ConfigService[F]] =
    Slf4jLogger
      .create[F]
      .map(
        new ConfigService[F](
          activity,
          button,
          remote,
          name => macros.getConfig.map(_.values.get(name)),
          room => currentActivity.getConfig.map(_.values.get(room)),
          switches,
          _
        ) {}
      )

  def apply[F[_]: Sync: Parallel: Trace: ConfigServiceErrors](
    activity: WritableConfigSource[F, String, Activity],
    button: WritableConfigSource[F, String, Button],
    remote: WritableConfigSource[F, NonEmptyString, Remote],
    macros: MacroStore[F],
    activityStore: ActivityStore[F],
    switches: Switches[F],
    validation: ConfigValidation[F],
    configEventPublisher: EventPublisher[F, ConfigEvent]
  ): F[WritableConfigService[F]] =
    Slf4jLogger
      .create[F]
      .map(
        new WritableConfigService[F](
          activity,
          button,
          remote,
          macros,
          activityStore,
          switches,
          validation,
          configEventPublisher,
          _
        )
      )
}
