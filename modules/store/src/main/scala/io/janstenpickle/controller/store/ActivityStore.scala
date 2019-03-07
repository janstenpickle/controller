package io.janstenpickle.controller.store

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Room

trait ActivityStore[F[_]] {
  def storeActivity(room: Room, name: NonEmptyString): F[Unit]
  def loadActivity(room: Room): F[Option[NonEmptyString]]
}
