package io.janstenpickle.controller.configsource

import io.janstenpickle.controller.model.MultiSwitches

trait MultiSwitchConfigSource[F[_]] {
  def getMultiSwitches: F[MultiSwitches]
}
