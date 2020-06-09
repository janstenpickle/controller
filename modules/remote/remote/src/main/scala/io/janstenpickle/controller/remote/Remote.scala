package io.janstenpickle.controller.remote

import eu.timepit.refined.types.string.NonEmptyString

trait Remote[F[_], T] {
  def name: NonEmptyString
  def learn: F[Option[T]]
  def sendCommand(payload: T): F[Unit]
}
