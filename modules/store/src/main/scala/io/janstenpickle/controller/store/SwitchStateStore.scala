package io.janstenpickle.controller.store

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State

trait SwitchStateStore[F[_]] {
  def setOn(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit]
  def setOff(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[Unit]
  def getState(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString): F[State]
}
