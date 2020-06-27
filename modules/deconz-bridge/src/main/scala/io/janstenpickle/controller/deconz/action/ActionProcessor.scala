package io.janstenpickle.controller.deconz.action

import io.janstenpickle.controller.deconz.model.ButtonAction

trait ActionProcessor[F[_]] {
  def process(id: String, action: ButtonAction): F[Unit]
}
