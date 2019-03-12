package io.janstenpickle.controller.activity

import cats.{Apply, Monad, MonadError}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.applicative._
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
    switch: Switch[F],
    activities: ActivityStore[F],
    macros: Macro[F],
    onUpdate: ((Room, NonEmptyString)) => F[Unit]
  ): Activity[F] = {
    val underlying = apply[F](activities, macros, onUpdate)

    new Activity[F] {
      override def setActivity(room: Room, name: NonEmptyString): F[Unit] =
        switch.getState.flatMap {
          case State.On => underlying.setActivity(room, name)
          case State.Off => ().pure
        }

      override def getActivity(room: Room): F[Option[NonEmptyString]] = underlying.getActivity(room)
    }
  }

  def dependsOnSwitch[F[_]](
    switchDevice: NonEmptyString,
    switchName: NonEmptyString,
    switchProvider: SwitchProvider[F],
    activities: ActivityStore[F],
    macros: Macro[F],
    onUpdate: ((Room, NonEmptyString)) => F[Unit]
  )(implicit F: MonadError[F, Throwable]): F[Activity[F]] =
    switchProvider.getSwitches.flatMap(_.get(SwitchKey(switchDevice, switchName)) match {
      case None =>
        F.raiseError[Activity[F]](
          new RuntimeException(s"Could not find switch '${switchName.value}' of device ${switchDevice.value}")
        )
      case Some(switch) => F.pure(dependsOnSwitch[F](switch, activities, macros, onUpdate))
    })
}
