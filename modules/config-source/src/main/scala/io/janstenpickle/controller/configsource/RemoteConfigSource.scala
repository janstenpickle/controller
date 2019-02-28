package io.janstenpickle.controller.configsource

import cats.Apply
import cats.syntax.apply._
import io.janstenpickle.controller.model.Remotes

trait RemoteConfigSource[F[_]] {
  def getRemotes: F[Remotes]
}

object RemoteConfigSource {
  def combined[F[_]: Apply](x: RemoteConfigSource[F], y: RemoteConfigSource[F]): RemoteConfigSource[F] =
    new RemoteConfigSource[F] {
      override def getRemotes: F[Remotes] = x.getRemotes.map2(y.getRemotes) { (x0, y0) =>
        Remotes(x0.remotes ++ y0.remotes, x0.errors ++ y0.errors)
      }
    }
}
