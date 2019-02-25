package io.janstenpickle.controller.remote

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.CommandPayload

trait Remote[F[_]] {
  def name: NonEmptyString
  def learn: F[Option[CommandPayload]]
  def sendCommand(payload: CommandPayload): F[Unit]
}
