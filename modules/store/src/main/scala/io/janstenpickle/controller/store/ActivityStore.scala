package io.janstenpickle.controller.store

import eu.timepit.refined.types.string.NonEmptyString

trait ActivityStore[F[_]] {
  def storeActivity(name: NonEmptyString): F[Unit]
  def loadActivity: F[Option[NonEmptyString]]
}
