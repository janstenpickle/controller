package io.janstenpickle.controller.configsource

import cats.kernel.Semigroup
import cats.{Monad, Parallel}

trait WritableConfigSource[F[_], A] extends ConfigSource[F, A] {
  def setConfig(a: A): F[Unit]
  def mergeConfig(a: A): F[A]
}

object WritableConfigSource {
  def combined[F[_]: Monad: Parallel, A: Semigroup](
    writable: WritableConfigSource[F, A],
    other: ConfigSource[F, A]
  ): WritableConfigSource[F, A] =
    new WritableConfigSource[F, A] {
      private val combined = ConfigSource.combined(writable, other)

      override def mergeConfig(a: A): F[A] = writable.mergeConfig(a)
      override def setConfig(a: A): F[Unit] = writable.setConfig(a)
      override def getConfig: F[A] = combined.getConfig
    }
}
