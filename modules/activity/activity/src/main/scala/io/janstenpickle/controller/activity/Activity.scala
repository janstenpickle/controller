package io.janstenpickle.controller.activity

import cats.effect.Clock
import cats.syntax.applicativeError._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.{Applicative, Apply, MonadError, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.activity.store.ActivityStore
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.ActivityUpdateEvent
import io.janstenpickle.controller.model.{Room, State, SwitchKey, Activity => ActivityModel}
import io.janstenpickle.controller.switch.SwitchProvider
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.AttributeValue.StringValue
import io.janstenpickle.trace4cats.model.{AttributeValue, SpanStatus}

trait Activity[F[_]] {
  def setActivity(room: Room, name: NonEmptyString): F[Unit]
  def getActivity(room: Room): F[Option[NonEmptyString]]
}

object Activity {
  private def span[F[_]: Apply, A](name: String, room: Room, extraFields: (String, AttributeValue)*)(
    k: F[A]
  )(implicit trace: Trace[F]): F[A] = trace.span(name) {
    trace.putAll(extraFields :+ "room" -> StringValue(room.value): _*) *> k
  }

  def noop[F[_]: Applicative]: Activity[F] = new Activity[F] {
    override def setActivity(room: Room, name: NonEmptyString): F[Unit] = Applicative[F].unit
    override def getActivity(room: Room): F[Option[NonEmptyString]] = Applicative[F].pure(None)
  }

  def apply[F[_]: Clock](
    config: ConfigSource[F, String, ActivityModel],
    activities: ActivityStore[F],
    macros: Macro[F],
    activityEventPublisher: EventPublisher[F, ActivityUpdateEvent]
  )(implicit F: MonadError[F, Throwable], trace: Trace[F]): Activity[F] = new Activity[F] {
    override def setActivity(room: Room, name: NonEmptyString): F[Unit] =
      span("set.activity", room, "activity" -> name.value) {
        (config
          .getValue(name.value)
          .flatMap(
            _.flatMap(_.action).fold(
              macros.maybeExecuteMacro(NonEmptyString.unsafeFrom(s"${room.value}-${name.value}"))
            )(macros.executeCommand)
          ) *> activities
          .storeActivity(room, name) *> activityEventPublisher.publish1(ActivityUpdateEvent(room, name)))
          .handleErrorWith { th =>
            activityEventPublisher.publish1(ActivityUpdateEvent(room, name, Some(th.getMessage))) *> th.raiseError
          }
      }

    override def getActivity(room: Room): F[Option[NonEmptyString]] = span("get.activity", room) {
      activities.loadActivity(room)
    }
  }

  def dependsOnSwitch[F[_]: Parallel: Clock](
    switches: Map[Room, SwitchKey],
    switchProvider: SwitchProvider[F],
    config: ConfigSource[F, String, ActivityModel],
    activities: ActivityStore[F],
    macros: Macro[F],
    activityEventPublisher: EventPublisher[F, ActivityUpdateEvent]
  )(implicit F: MonadError[F, Throwable], trace: Trace[F]): Activity[F] = {
    val underlying = apply[F](config, activities, macros, activityEventPublisher)

    dependsOnSwitch[F](switches, switchProvider, underlying)
  }

  def dependsOnSwitch[F[_]: Parallel: Clock](
    switches: Map[Room, SwitchKey],
    switchProvider: SwitchProvider[F],
    underlying: Activity[F]
  )(implicit F: MonadError[F, Throwable], trace: Trace[F]): Activity[F] =
    new Activity[F] {
      override def setActivity(room: Room, name: NonEmptyString): F[Unit] =
        span("depends.on.switch.set.activity", room, "activity" -> name.value) {
          switches.get(room).fold(underlying.setActivity(room, name)) { key =>
            trace
              .putAll("switch.device" -> key.device.value, "switch.name" -> key.name.value) *> switchProvider.getSwitches
              .flatMap { sws =>
                sws.get(key) match {
                  case None =>
                    trace.setStatus(SpanStatus.NotFound) *> F.raiseError(
                      new RuntimeException(
                        s"Could not find switch '${key.name.value}' of device '${key.device.value}' for room '${room.value}'"
                      )
                    )
                  case Some(switch) =>
                    switch.getState.flatMap {
                      case State.On => trace.put("switch.on", true) *> underlying.setActivity(room, name)
                      case State.Off => trace.put("switch.on", false)
                    }
                }
              }
          }
        }

      override def getActivity(room: Room): F[Option[NonEmptyString]] = span("depends.on.switch.get.activity", room) {
        underlying.getActivity(room)
      }
    }

}
