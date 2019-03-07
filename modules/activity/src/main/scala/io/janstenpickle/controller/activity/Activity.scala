package io.janstenpickle.controller.activity

import cats.Apply
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.model.Room
import io.janstenpickle.controller.store.ActivityStore

class Activity[F[_]: Apply](
  activities: ActivityStore[F],
  macros: Macro[F],
  onUpdate: ((Room, NonEmptyString)) => F[Unit]
) {
  def setActivity(room: Room, name: NonEmptyString): F[Unit] =
    macros.maybeExecuteMacro(NonEmptyString.unsafeFrom(s"${room.value}-${name.value}")) *> activities
      .storeActivity(room, name) *> onUpdate(room -> name)

  def getActivity(room: Room): F[Option[NonEmptyString]] = activities.loadActivity(room)
}
