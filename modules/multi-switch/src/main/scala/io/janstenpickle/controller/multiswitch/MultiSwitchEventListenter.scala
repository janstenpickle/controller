package io.janstenpickle.controller.multiswitch

import cats.data.Reader
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource}
import cats.instances.map._
import cats.instances.set._
import cats.syntax.semigroup._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.model.event.ConfigEvent.{MultiSwitchAddedEvent, MultiSwitchRemovedEvent}
import io.janstenpickle.controller.model.event.SwitchEvent.{
  SwitchAddedEvent,
  SwitchRemovedEvent,
  SwitchStateUpdateEvent
}
import io.janstenpickle.controller.model.event.{ConfigEvent, SwitchEvent}
import io.janstenpickle.controller.model.{State, SwitchKey}

object MultiSwitchEventListenter {
  case class EventsState(
    switchStates: Map[SwitchKey, State],
    primariesReverseMapping: Map[SwitchKey, Set[NonEmptyString]]
  )

  private def handleConfigEvent(
    event: ConfigEvent
  ): Reader[EventsState, (Map[SwitchKey, Set[NonEmptyString]], List[SwitchEvent])] =
    Reader { state =>
      event match {
        case MultiSwitchAddedEvent(multiSwitch, _) =>
          val key = SwitchKey(DeviceName, multiSwitch.name)
          val addedEvent = SwitchAddedEvent(key, makeMetadata(multiSwitch))

          val events =
            state.switchStates
              .get(SwitchKey(multiSwitch.primary.device, multiSwitch.primary.name))
              .fold[List[SwitchEvent]](List(addedEvent)) { state =>
                List(addedEvent, SwitchStateUpdateEvent(key, state))
              }

          val primaryKey = SwitchKey(multiSwitch.primary.device, multiSwitch.primary.name)

          (state.primariesReverseMapping |+| Map(primaryKey -> Set(multiSwitch.name)), events)
        case MultiSwitchRemovedEvent(name, _) =>
          val reverse = state.primariesReverseMapping.flatMap {
            case (key, multis) =>
              val updated = multis - name
              if (updated.isEmpty) None
              else Some((key, updated))
          }

          (reverse, List(SwitchRemovedEvent(SwitchKey(DeviceName, name))))
        case _ => (state.primariesReverseMapping, List.empty)
      }
    }

  private def handleSwitchUpdateEvent(
    event: SwitchStateUpdateEvent
  ): Reader[EventsState, (Map[SwitchKey, State], List[SwitchEvent])] = Reader { state =>
    state.primariesReverseMapping.get(event.key) match {
      case None => (state.switchStates.updated(event.key, event.state), List.empty)
      case Some(multiSwitchNames) =>
        (state.switchStates.updated(event.key, event.state), multiSwitchNames.toList.map { multiSwitchName =>
          SwitchStateUpdateEvent(SwitchKey(DeviceName, multiSwitchName), event.state)
        })
    }
  }

  def apply[F[_]: Concurrent](
    switchEvents: EventPubSub[F, SwitchEvent],
    configEvents: EventPubSub[F, ConfigEvent]
  ): Resource[F, Unit] =
    Resource
      .make(
        switchEvents.subscriberStream
          .collect {
            case e: SwitchStateUpdateEvent => e
          }
          .subscribe
          .either(configEvents.subscriberStream.subscribe)
          .mapAccumulate(EventsState(Map.empty, Map.empty)) { (state, event) =>
            (event match {
              case Right(configEvent) =>
                handleConfigEvent(configEvent).map {
                  case (primaries, events) =>
                    (state.copy(primariesReverseMapping = primaries), events)
                }
              case Left(switchEvent) =>
                handleSwitchUpdateEvent(switchEvent).map {
                  case (switchStates, events) => (state.copy(switchStates = switchStates), events)
                }
            }).run(state)
          }
          .flatMap { case (_, events) => Stream.emits(events) }
          .through(switchEvents.publisher.pipe)
          .compile
          .drain
          .start
      )(_.cancel)
      .map(_ => ())

}
