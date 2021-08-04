package io.janstenpickle.controller.event.switch

import cats.effect.kernel.{Async, Outcome}
import cats.effect.syntax.spawn._
import cats.effect.{MonadCancelThrow, Resource}
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, FlatMap}
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import io.janstenpickle.controller.cache.{Cache, CacheResource}
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
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import io.janstenpickle.trace4cats.model.AttributeValue

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object EventDrivenSwitchProvider {

  def apply[F[_]: Async, G[_]: MonadCancelThrow](
    eventSubscriber: EventSubscriber[F, SwitchEvent],
    commandPublisher: EventPublisher[F, CommandEvent],
    source: String,
    commandTimeout: FiniteDuration,
    k: ResourceKleisli[G, (SpanName, Map[String, String]), Span[G]],
    cacheTimeout: FiniteDuration = 20.minutes
  )(
    implicit errors: SwitchErrors[F],
    trace: Trace[F],
    provide: Provide[G, F, Span[G]]
  ): Resource[F, SwitchProvider[F]] = {

    def makeSwitch(key: SwitchKey, meta: SwitchMetadata, states: Cache[F, SwitchKey, State]): Switch[F] =
      TracedSwitch(new Switch[F] {
        override def name: NonEmptyString = key.name
        override def device: NonEmptyString = key.device
        override def metadata: SwitchMetadata = meta
        override def getState: F[State] = states.get(key).map(_.getOrElse(State.Off))
        override def switchOn: F[Unit] =
          waitFor(SwitchKey(device, name), Command.SwitchOn(device, name))
        override def switchOff: F[Unit] =
          waitFor(SwitchKey(device, name), Command.SwitchOff(device, name))
        override def toggle(implicit F: FlatMap[F]): F[Unit] =
          waitFor(SwitchKey(device, name), Command.ToggleSwitch(device, name))
      })

    def span[A](name: String, switchKey: SwitchKey, extraFields: (String, AttributeValue)*)(f: F[A]): F[A] =
      trace.span(name) {
        trace.putAll(
          extraFields ++ List[(String, AttributeValue)](
            "switch.name" -> switchKey.name.value,
            "switch.device" -> switchKey.device.value
          ): _*
        ) *> f
      }

    def listen(switches: Cache[F, SwitchKey, Switch[F]], states: Cache[F, SwitchKey, State]) =
      eventSubscriber.filterEvent(_.source != source).subscribeEvent.evalMapTrace("switch.receive", k) {
        case SwitchAddedEvent(key, metadata) =>
          span("switch.added", key)(switches.set(key, makeSwitch(key, metadata, states)))
        case SwitchRemovedEvent(key) =>
          span("switch.removed", key)(switches.remove(key) >> states.remove(key))
        case SwitchStateUpdateEvent(key, state, None) =>
          span("switch.state.update", key, "switch.state" -> state.isOn)(states.set(key, state))
        case _ => Applicative[F].unit
      }

    def listener(
      switches: Cache[F, SwitchKey, Switch[F]],
      states: Cache[F, SwitchKey, State]
    ): Resource[F, F[Outcome[F, Throwable, Unit]]] =
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
      switches <- CacheResource.caffeine[F, SwitchKey, Switch[F]](cacheTimeout)
      states <- CacheResource.caffeine[F, SwitchKey, State](cacheTimeout)
      _ <- listener(switches, states)
    } yield
      new SwitchProvider[F] {
        override def getSwitches: F[Map[SwitchKey, Switch[F]]] = switches.getAll
      }
  }
}
