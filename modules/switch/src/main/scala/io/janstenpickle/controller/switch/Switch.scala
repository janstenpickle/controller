package io.janstenpickle.controller.switch

import cats.FlatMap
import cats.syntax.flatMap._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State

trait Switch[F[_]] {
  def name: NonEmptyString
  def device: NonEmptyString
  def getState: F[State]
  def switchOn: F[Unit]
  def switchOff: F[Unit]
  def toggle(implicit F: FlatMap[F]): F[Unit] =
    getState.flatMap {
      case State.On => switchOff
      case State.Off => switchOn
    }
}
