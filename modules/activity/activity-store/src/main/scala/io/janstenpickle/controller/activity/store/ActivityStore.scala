package io.janstenpickle.controller.activity.store

import cats.Functor
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.model.Room

trait ActivityStore[F[_]] {
  def storeActivity(room: Room, name: NonEmptyString): F[Unit]
  def loadActivity(room: Room): F[Option[NonEmptyString]]
}

object ActivityStore {
  def fromConfigSource[F[_]: Functor](source: WritableConfigSource[F, Room, NonEmptyString]): ActivityStore[F] =
    new ActivityStore[F] {
      override def storeActivity(room: Room, name: NonEmptyString): F[Unit] =
        source.upsert(room, name).void

      override def loadActivity(room: Room): F[Option[NonEmptyString]] =
        source.getValue(room)
    }
}
