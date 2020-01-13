package io.janstenpickle.controller.context

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.errors.ErrorHandler
import io.janstenpickle.controller.model.Room

trait ContextErrors[F[_]] { self: ErrorHandler[F] =>
  def activityNotSet[A](room: Room): F[A]
  def activityNotPresent[A](room: Room, activity: NonEmptyString): F[A]
}
