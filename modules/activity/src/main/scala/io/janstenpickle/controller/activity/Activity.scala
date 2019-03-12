package io.janstenpickle.controller.activity

import cats.{Apply, Monad, MonadError}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.applicative._
import cats.syntax.traverse._
import cats.instances.list._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.model.{Room, State}
import io.janstenpickle.controller.store.ActivityStore
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}

trait Activity[F[_]] {
  def setActivity(room: Room, name: NonEmptyString): F[Unit]
  def getActivity(room: Room): F[Option[NonEmptyString]]
}

object Activity {
  def apply[F[_]: Apply](
    activities: ActivityStore[F],
    macros: Macro[F],
    onUpdate: ((Room, NonEmptyString)) => F[Unit]
  ): Activity[F] = new Activity[F] {
    override def setActivity(room: Room, name: NonEmptyString): F[Unit] =
      macros.maybeExecuteMacro(NonEmptyString.unsafeFrom(s"${room.value}-${name.value}")) *> activities
        .storeActivity(room, name) *> onUpdate(room -> name)

    override def getActivity(room: Room): F[Option[NonEmptyString]] = activities.loadActivity(room)
  }

  def dependsOnSwitch[F[_]: Monad](
    switches: Map[Room, Switch[F]],
    activities: ActivityStore[F],
    macros: Macro[F],
    onUpdate: ((Room, NonEmptyString)) => F[Unit]
  ): Activity[F] = {
    val underlying = apply[F](activities, macros, onUpdate)

    new Activity[F] {
      override def setActivity(room: Room, name: NonEmptyString): F[Unit] =
        switches.get(room).fold(underlying.setActivity(room, name)) { switch =>
          switch.getState.flatMap {
            case State.On => underlying.setActivity(room, name)
            case State.Off => ().pure[F]
          }
        }

      override def getActivity(room: Room): F[Option[NonEmptyString]] = underlying.getActivity(room)
    }
  }

  def dependsOnSwitch[F[_]](
    switches: Map[Room, SwitchKey],
    switchProvider: SwitchProvider[F],
    activities: ActivityStore[F],
    macros: Macro[F],
    onUpdate: ((Room, NonEmptyString)) => F[Unit]
  )(implicit F: MonadError[F, Throwable]): F[Activity[F]] =
    switchProvider.getSwitches.flatMap { sws =>
      switches.toList
        .traverse[F, (Room, Switch[F])] {
          case (room, key) =>
            sws.get(key) match {
              case None =>
                F.raiseError(
                  new RuntimeException(
                    s"Could not find switch '${key.name.value}' of device '${key.device.value}' for room '${room.value}'"
                  )
                )
              case Some(switch) => F.pure((room, switch))
            }
        }
        .map { s =>
          dependsOnSwitch[F](s.toMap, activities, macros, onUpdate)
        }
    }

}
