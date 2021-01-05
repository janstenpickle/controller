package io.janstenpickle.controller.stats

import cats.instances.map._
import cats.instances.set._
import cats.kernel.Semigroup
import cats.syntax.semigroup._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.event.ConfigEvent
import io.janstenpickle.controller.model.{Activity, Button, Remote}

import scala.collection.compat._

object ConfigStatsTranslator {
  private val All = NonEmptyString("all")

  private case class ConfigState(
    remotes: Map[String, Map[NonEmptyString, Remote]] = Map.empty,
    activities: Map[String, Map[NonEmptyString, Activity]] = Map.empty,
    buttons: Map[String, Map[NonEmptyString, Button]] = Map.empty,
    macros: Map[String, Map[NonEmptyString, Int]] = Map.empty
  )

  private def remotesStateToStat(state: Map[String, Map[NonEmptyString, Remote]]) = {
    val remotes = state.flatMap(_._2.values)

    val roomActivity = remotes
      .flatMap { remote =>
        val rooms =
          if (remote.rooms.isEmpty) List(All)
          else remote.rooms

        val activities =
          if (remote.activities.isEmpty) List(All)
          else remote.activities

        rooms.map(_ -> activities.map(_ -> remote.name))
      }
      .groupBy(_._1)
      .view
      .mapValues(_.flatMap(_._2.groupBy(_._1).mapValues(_.size)).groupBy(_._1).view.mapValues(_.map(_._2).sum).toMap)
      .toMap

    val remoteButtons = remotes
      .map(
        r =>
          r.name -> r.buttons
            .groupBy(buttonType)
            .view
            .mapValues(_.size)
            .toMap
      )
      .toMap

    Stats.Remotes(remotes.size, roomActivity, remoteButtons)
  }

  private def activityStateToStat(state: Map[String, Map[NonEmptyString, Activity]]): Stats = {
    val groupedActivities = state.flatMap(_._2.values).groupBy(_.room)

    Stats.Activities(
      groupedActivities.view.mapValues(_.size).toMap,
      groupedActivities.view
        .mapValues(_.map(a => a.name -> a.contextButtons.size).toMap)
        .toMap
    )
  }

  private def buttonsStateToStat(buttons: Map[String, Map[NonEmptyString, Button]]): Stats =
    Stats.Buttons(
      buttons
        .flatMap(_._2.values)
        .groupBy(_.room.getOrElse(All))
        .view
        .mapValues(_.groupBy(buttonType).view.mapValues(_.size).toMap)
        .toMap
    )

  private def macrosStateToStat(macros: Map[String, Map[NonEmptyString, Int]]): Stats = {
    val allMacros = macros.flatMap(_._2)
    Stats.Macros(allMacros.size, allMacros)
  }

  private implicit def nesMapSemigroup[A]: Semigroup[Map[NonEmptyString, A]] =
    new Semigroup[Map[NonEmptyString, A]] {
      override def combine(x: Map[NonEmptyString, A], y: Map[NonEmptyString, A]): Map[NonEmptyString, A] = x ++ y
    }

  def apply[F[_]](configSubscriber: EventSubscriber[F, ConfigEvent]): Stream[F, Stats] =
    configSubscriber.subscribe
      .mapAccumulate[ConfigState, Option[Stats]](ConfigState()) {
        case (state, ConfigEvent.RemoteAddedEvent(remote, source)) =>
          val newRemotes = state.remotes |+| Map(source -> Map(remote.name -> remote))
          (state.copy(newRemotes), Some(remotesStateToStat(newRemotes)))
        case (state, ConfigEvent.RemoteRemovedEvent(name, src)) =>
          val newRemotes = state.remotes.flatMap {
            case (source, remotes) if source == src =>
              val nr = remotes - name
              if (nr.nonEmpty) List(source -> nr)
              else List.empty
            case x => List(x)
          }
          (state.copy(newRemotes), Some(remotesStateToStat(newRemotes)))

        case (state, ConfigEvent.ActivityAddedEvent(activity, source)) =>
          val newActivities = state.activities |+| Map(source -> Map(activity.name -> activity))
          (state.copy(activities = newActivities), Some(activityStateToStat(newActivities)))
        case (state, ConfigEvent.ActivityRemovedEvent(room, activityName, src)) =>
          val na = state.activities.flatMap {
            case (source, activities) if source == src =>
              val newActs = activities.filterNot { case (_, a) => a.name == activityName && a.room == room }
              if (newActs.nonEmpty) List(source -> newActs)
              else List.empty
            case x => List(x)
          }
          (state.copy(activities = na), Some(activityStateToStat(na)))

        case (state, ConfigEvent.ButtonAddedEvent(button, source)) =>
          val newButtons = state.buttons |+| Map(source -> Map(button.name -> button))
          (state.copy(buttons = newButtons), Some(buttonsStateToStat(newButtons)))

        case (state, ConfigEvent.ButtonRemovedEvent(name, room, src)) =>
          val newButtons = state.buttons.flatMap {
            case (source, buttons) if source == src =>
              val nb = buttons.filterNot { case (_, b) => b.name == name && b.room == room }
              if (nb.nonEmpty) List(source -> nb)
              else List.empty
            case x => List(x)
          }
          (state.copy(buttons = newButtons), Some(buttonsStateToStat(newButtons)))

        case (state, ConfigEvent.MacroAddedEvent(name, commands, source)) =>
          val newMacros = state.macros |+| Map(source -> Map(name -> commands.size))
          (state.copy(macros = newMacros), Some(macrosStateToStat(newMacros)))

        case (state, ConfigEvent.MacroRemovedEvent(name, src)) =>
          val newMacros = state.macros.flatMap {
            case (source, macros) if source == src =>
              val nm = macros.view.filterKeys(_ != name).toMap
              if (nm.nonEmpty) List(source -> nm)
              else List.empty
            case x => List(x)
          }
          (state.copy(macros = newMacros), Some(macrosStateToStat(newMacros)))

        case (state, _) => (state, None)

      }
      .map(_._2)
      .unNone
}
