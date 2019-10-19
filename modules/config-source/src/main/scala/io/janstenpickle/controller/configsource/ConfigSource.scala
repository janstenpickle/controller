package io.janstenpickle.controller.configsource

import cats.kernel.Semigroup
import cats.{Monad, Parallel}

trait ConfigSource[F[_], K, V] { outer =>
  def getValue(key: K): F[Option[V]]
  def getConfig: F[ConfigResult[K, V]]
}

object ConfigSource {
  def combined[F[_]: Monad: Parallel, K, V](x: ConfigSource[F, K, V], y: ConfigSource[F, K, V])(
    implicit semigroup: Semigroup[ConfigResult[K, V]]
  ): ConfigSource[F, K, V] =
    new ConfigSource[F, K, V] {
      override def getConfig: F[ConfigResult[K, V]] =
        Parallel.parMap2(x.getConfig, y.getConfig)(semigroup.combine)

      override def getValue(key: K): F[Option[V]] =
        Parallel.parMap2(x.getValue(key), y.getValue(key)) {
          case (Some(xx), Some(_)) => Some(xx)
          case (None, Some(yy)) => Some(yy)
          case (Some(xx), None) => Some(xx)
          case (None, None) => None
        }
    }
}
