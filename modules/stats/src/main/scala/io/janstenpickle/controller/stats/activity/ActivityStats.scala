package io.janstenpickle.controller.stats.activity

import cats.Apply
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.activity.Activity
import io.janstenpickle.controller.model.Room

object ActivityStats {
  def apply[F[_]: Apply](underlying: Activity[F])(implicit stats: ActivityStatsRecorder[F]): Activity[F] =
    new Activity[F] {
      override def setActivity(room: Room, name: NonEmptyString): F[Unit] =
        underlying.setActivity(room, name) *> stats.recordSetActivity(room, name)

      override def getActivity(room: Room): F[Option[NonEmptyString]] = underlying.getActivity(room)
    }
}
