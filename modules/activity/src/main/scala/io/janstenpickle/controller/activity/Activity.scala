package io.janstenpickle.controller.activity

import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.{Apply, Monad, MonadError, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.model.{Room, State, Activity => ActivityModel}
import io.janstenpickle.controller.store.ActivityStore
import io.janstenpickle.controller.switch.SwitchProvider
import io.janstenpickle.controller.switch.model.SwitchKey
import natchez.TraceValue.StringValue
import natchez.{Trace, TraceValue}

trait Activity[F[_]] {
  def setActivity(room: Room, name: NonEmptyString): F[Unit]
  def getActivity(room: Room): F[Option[NonEmptyString]]
}

object Activity {
  private def span[F[_]: Apply, A](name: String, room: Room, extraFields: (String, TraceValue)*)(
    k: F[A]
  )(implicit trace: Trace[F]): F[A] = trace.span(name) {
    trace.put(extraFields :+ "room" -> StringValue(room.value): _*) *> k
  }

  def apply[F[_]](
    config: ConfigSource[F, String, ActivityModel],
    activities: ActivityStore[F],
    macros: Macro[F],
    onUpdate: ((Room, NonEmptyString)) => F[Unit]
  )(implicit F: Monad[F], trace: Trace[F]): Activity[F] = new Activity[F] {
    override def setActivity(room: Room, name: NonEmptyString): F[Unit] =
      span("setActivity", room, "activity" -> name.value) {
        config
          .getValue(name.value)
          .flatMap(
            _.flatMap(_.action).fold(
              macros.maybeExecuteMacro(NonEmptyString.unsafeFrom(s"${room.value}-${name.value}"))
            )(macros.executeCommand)
          ) *> activities
          .storeActivity(room, name) *> onUpdate(room -> name)
      }

    override def getActivity(room: Room): F[Option[NonEmptyString]] = span("getActivity", room) {
      activities.loadActivity(room)
    }
  }

  def dependsOnSwitch[F[_]: Parallel](
    switches: Map[Room, SwitchKey],
    switchProvider: SwitchProvider[F],
    config: ConfigSource[F, String, ActivityModel],
    activities: ActivityStore[F],
    macros: Macro[F],
    onUpdate: ((Room, NonEmptyString)) => F[Unit]
  )(implicit F: MonadError[F, Throwable], trace: Trace[F]): Activity[F] = {
    val underlying = apply[F](config, activities, macros, onUpdate)

    new Activity[F] {
      override def setActivity(room: Room, name: NonEmptyString): F[Unit] =
        span("dependsOnSwitchSetActivity", room, "activity" -> name.value) {
          switches.get(room).fold(underlying.setActivity(room, name)) { key =>
            trace
              .put("switch.device" -> key.device.value, "switch.name" -> key.name.value) *> switchProvider.getSwitches
              .flatMap { sws =>
                sws.get(key) match {
                  case None =>
                    trace.put("error" -> true, "reason" -> "switch not found") *> F.raiseError(
                      new RuntimeException(
                        s"Could not find switch '${key.name.value}' of device '${key.device.value}' for room '${room.value}'"
                      )
                    )
                  case Some(switch) =>
                    switch.getState.flatMap {
                      case State.On => trace.put("switch.on" -> true) *> underlying.setActivity(room, name)
                      case State.Off => trace.put("switch.on" -> false)
                    }
                }
              }
          }
        }

      override def getActivity(room: Room): F[Option[NonEmptyString]] = span("dependsOnSwitchGetActivity", room) {
        underlying.getActivity(room)
      }
    }
  }

}
