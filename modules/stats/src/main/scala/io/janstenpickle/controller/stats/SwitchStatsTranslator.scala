package io.janstenpickle.controller.stats

import fs2.Stream
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.model.event.SwitchEvent
import io.janstenpickle.controller.model.event.SwitchEvent.{
  SwitchAddedEvent,
  SwitchRemovedEvent,
  SwitchStateUpdateEvent
}
import io.janstenpickle.controller.model.{State, SwitchKey, SwitchMetadata}

object SwitchStatsTranslator {
  private def switchesStateToStat(state: Map[SwitchKey, SwitchMetadata]): Stats.Switches =
    Stats.Switches(
      state.size,
      state.groupBy(_._2.`type`).view.mapValues(_.size).toMap,
      state.groupBy(_._2.room.getOrElse("")).view.mapValues(_.size).toMap,
      state.groupBy(_._1.device).view.mapValues(_.size).toMap
    )

  def apply[F[_]](switchSubscriber: EventSubscriber[F, SwitchEvent]): Stream[F, Stats] =
    switchSubscriber.subscribe
      .mapAccumulate(Map.empty[SwitchKey, SwitchMetadata]) {
        case (state, SwitchAddedEvent(key, metadata)) =>
          val newState = state.updated(key, metadata)
          (newState, List(switchesStateToStat(newState)))
        case (state, SwitchRemovedEvent(key)) =>
          val newState = state - key
          (newState, List(switchesStateToStat(newState)))
        case (st, SwitchStateUpdateEvent(key, state, None)) =>
          val onOff = state match {
            case State.On => Stats.SwitchOn(key.device, key.name)
            case State.Off => Stats.SwitchOff(key.device, key.name)
          }
          (st, List(Stats.SwitchState(key, state), onOff))

        case (st, SwitchStateUpdateEvent(key, _, Some(_))) =>
          (st, List(Stats.SwitchError(key.device, key.name)))
      }
      .flatMap { case (_, stats) => Stream.emits(stats) }

}
