package io.janstenpickle.controller.event.switch

import cats.Applicative
import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.applicativeError._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.chrisdavenport.mapref.MapRef
import io.janstenpickle.controller.events.{EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.event.SwitchEvent.{
  SwitchAddedEvent,
  SwitchRemovedEvent,
  SwitchStateUpdateEvent
}
import io.janstenpickle.controller.model.event.{CommandEvent, SwitchEvent}
import io.janstenpickle.controller.model.{Command, State, SwitchKey, SwitchMetadata}
import io.janstenpickle.controller.switch.{SwitchErrors, Switches}
import cats.syntax.eq._

import scala.concurrent.duration._
import io.janstenpickle.controller.events.syntax.all._

import scala.concurrent.TimeoutException

object EventDrivenSwitches {

  def apply[F[_]: Concurrent: Timer](
    eventSubscriber: EventSubscriber[F, SwitchEvent],
    commandPublisher: EventPublisher[F, CommandEvent],
    commandTimeout: FiniteDuration
  )(implicit errors: SwitchErrors[F]): Resource[F, Switches[F]] = {

    def listen(switches: Ref[F, Map[SwitchKey, SwitchMetadata]], states: MapRef[F, SwitchKey, Option[State]]) =
      eventSubscriber.subscribe.evalMap {
        case SwitchAddedEvent(key, metadata) =>
          switches.update(_.updated(key, metadata))
        case SwitchRemovedEvent(key) =>
          switches.update(_.removed(key))
        case SwitchStateUpdateEvent(key, state, None) =>
          states(key).set(Some(state))
        case _ => Applicative[F].unit
      }

    def listener(
      switches: Ref[F, Map[SwitchKey, SwitchMetadata]],
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
            new TimeoutException(s"Failed to set switch device ${key.device} name ${key.name}").raiseError[F, Unit]
          case Some(_) => Applicative[F].unit
        }

    for {
      switches <- Resource.liftF(Ref.of[F, Map[SwitchKey, SwitchMetadata]](Map.empty))
      states <- Resource.liftF(MapRef.ofConcurrentHashMap[F, SwitchKey, State]())
      _ <- listener(switches, states)
    } yield
      new Switches[F] {
        private def doIfPresent[A](device: NonEmptyString, name: NonEmptyString)(fa: F[A]): F[A] =
          switches.get.flatMap { sws =>
            if (sws.contains(SwitchKey(device, name))) fa
            else errors.missingSwitch[A](device, name)
          }

        override def getState(device: NonEmptyString, name: NonEmptyString): F[State] =
          states(SwitchKey(device, name)).get.map(_.getOrElse(State.Off))

        override def getMetadata(device: NonEmptyString, name: NonEmptyString): F[Option[SwitchMetadata]] =
          switches.get.map(_.get(SwitchKey(device, name)))

        override def switchOn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
          doIfPresent(device, name)(waitFor(SwitchKey(device, name), Command.SwitchOn(device, name)))

        override def switchOff(device: NonEmptyString, name: NonEmptyString): F[Unit] =
          doIfPresent(device, name)(waitFor(SwitchKey(device, name), Command.SwitchOn(device, name)))

        override def toggle(device: NonEmptyString, name: NonEmptyString): F[Unit] =
          doIfPresent(device, name)(waitFor(SwitchKey(device, name), Command.ToggleSwitch(device, name)))

        override def list: F[Set[SwitchKey]] = switches.get.map(_.keySet)
      }
  }
}
