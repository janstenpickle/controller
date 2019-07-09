package io.janstenpickle.controller.configsource

import cats.kernel.Semigroup
import cats.{Monad, Parallel}

trait ConfigSource[F[_], A] {
  def getConfig: F[A]
}

object ConfigSource {
  def combined[F[_]: Monad: Parallel, A](x: ConfigSource[F, A], y: ConfigSource[F, A])(
    implicit semigroup: Semigroup[A]
  ): ConfigSource[F, A] =
    new ConfigSource[F, A] {
      override def getConfig: F[A] =
        Parallel.parMap2(x.getConfig, y.getConfig)(semigroup.combine)
    }
}
