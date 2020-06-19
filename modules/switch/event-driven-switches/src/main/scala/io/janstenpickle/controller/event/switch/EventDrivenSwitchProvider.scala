package io.janstenpickle.controller.event.switch

import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, FlatMap}
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.chrisdavenport.mapref.MapRef
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.events.syntax.all._
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.event.SwitchEvent.{
  SwitchAddedEvent,
  SwitchRemovedEvent,
  SwitchStateUpdateEvent
}
import io.janstenpickle.controller.model.event.{CommandEvent, SwitchEvent}
import io.janstenpickle.controller.model.{Command, State, SwitchKey, SwitchMetadata}
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.switch.{Switch, SwitchErrors, SwitchProvider}
import natchez.{Trace, TraceValue}

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object EventDrivenSwitchProvider {

  def apply[F[_]: Concurrent: Timer, G[_]](
    eventSubscriber: EventSubscriber[F, SwitchEvent],
    commandPublisher: EventPublisher[F, CommandEvent],
    source: String,
    commandTimeout: FiniteDuration
  )(
    implicit errors: SwitchErrors[F],
    trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, SwitchProvider[F]] = {

    def makeSwitch(key: SwitchKey, meta: SwitchMetadata, states: MapRef[F, SwitchKey, Option[State]]): Switch[F] =
      TracedSwitch(new Switch[F] {
        override def name: NonEmptyString = key.name
        override def device: NonEmptyString = key.device
        override def metadata: SwitchMetadata = meta
        override def getState: F[State] = states(key).get.map(_.getOrElse(State.Off))
        override def switchOn: F[Unit] = waitFor(SwitchKey(device, name), Command.SwitchOn(device, name))
        override def switchOff: F[Unit] = waitFor(SwitchKey(device, name), Command.SwitchOn(device, name))
        override def toggle(implicit F: FlatMap[F]): F[Unit] =
          waitFor(SwitchKey(device, name), Command.ToggleSwitch(device, name))
      })

    def span[A](name: String, switchKey: SwitchKey, extraFields: (String, TraceValue)*)(f: F[A]): F[A] =
      trace.span(name) {
        trace.put(
          extraFields ++ List[(String, TraceValue)](
            "switch.name" -> switchKey.name.value,
            "switch.device" -> switchKey.device.value
          ): _*
        ) *> f
      }

    def listen(switches: Ref[F, Map[SwitchKey, Switch[F]]], states: MapRef[F, SwitchKey, Option[State]]) =
      eventSubscriber.filterEvent(_.source != source).subscribeEvent.evalMapTrace("switch.receive") {
        case SwitchAddedEvent(key, metadata) =>
          span("switch.added", key)(switches.update(_.updated(key, makeSwitch(key, metadata, states))))
        case SwitchRemovedEvent(key) =>
          span("switch.removed", key)(switches.update(_.removed(key)))
        case SwitchStateUpdateEvent(key, state, None) =>
          span("switch.state.update", key, "switch.state" -> state.isOn)(states(key).set(Some(state)))
        case _ => Applicative[F].unit
      }

    def listener(
      switches: Ref[F, Map[SwitchKey, Switch[F]]],
      states: MapRef[F, SwitchKey, Option[State]]
    ): Resource[F, F[Unit]] =
      Stream
        .retry(listen(switches, states).compile.drain, 5.seconds, _ + 1.second, Int.MaxValue)
        .compile
        .drain
        .background

    def waitFor(key: SwitchKey, command: Command) =
      eventSubscriber
        .waitFor(commandPublisher.publish1(CommandEvent.MacroCommand(command)), commandTimeout) {
          case SwitchStateUpdateEvent(k, _, None) => k === key
        }
        .flatMap {
          case None =>
            (new TimeoutException(s"Failed to set switch device ${key.device} name ${key.name}")
            with NoStackTrace).raiseError[F, Unit]
          case Some(_) => Applicative[F].unit
        }

    for {
      switches <- Resource.liftF(Ref.of[F, Map[SwitchKey, Switch[F]]](Map.empty))
      states <- Resource.liftF(MapRef.ofConcurrentHashMap[F, SwitchKey, State]())
      _ <- listener(switches, states)
    } yield
      new SwitchProvider[F] {
        override def getSwitches: F[Map[SwitchKey, Switch[F]]] = switches.get
      }
  }
}
