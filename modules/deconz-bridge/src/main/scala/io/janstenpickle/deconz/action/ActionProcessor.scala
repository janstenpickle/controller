package io.janstenpickle.deconz.action

import io.janstenpickle.deconz.model.ButtonAction

trait ActionProcessor[F[_]] {
  def process(id: String, action: ButtonAction): F[Unit]
}
