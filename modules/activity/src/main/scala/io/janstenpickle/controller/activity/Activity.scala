package io.janstenpickle.controller.activity

import cats.Apply
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.`macro`.Macro
import io.janstenpickle.controller.store.ActivityStore

class Activity[F[_]: Apply](activities: ActivityStore[F], macros: Macro[F]) {
  def setActivity(name: NonEmptyString): F[Unit] =
    macros.executeMacro(name) *> activities.storeActivity(name)

  def getActivity: F[Option[NonEmptyString]] = activities.loadActivity
}
