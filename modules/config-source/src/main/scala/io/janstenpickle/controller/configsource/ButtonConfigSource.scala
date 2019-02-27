package io.janstenpickle.controller.configsource

import io.janstenpickle.controller.model.Buttons

trait ButtonConfigSource[F[_]] {
  def getCommonButtons: F[Buttons]
}
