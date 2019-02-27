package io.janstenpickle.controller.configsource

import io.janstenpickle.controller.model.Remotes

trait RemoteConfigSource[F[_]] {
  def getRemotes: F[Remotes]
}
