package io.janstenpickle.controller.configsource

import io.janstenpickle.controller.model.VirtualSwitches

trait VirtualSwitchConfigSource[F[_]] {
  def getVirtualSwitches: F[VirtualSwitches]
}
