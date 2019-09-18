package io.janstenpickle.controller.configsource

import cats.kernel.Semigroup
import cats.{Monad, Parallel}

trait WritableConfigSource[F[_], A, K] extends ConfigSource[F, A] {
  def setConfig(a: A): F[Unit]
  def mergeConfig(a: A): F[A]
  def deleteItem(key: K): F[A]
}

object WritableConfigSource {
  def combined[F[_]: Monad: Parallel, A: Semigroup, K](
    writable: WritableConfigSource[F, A, K],
    other: ConfigSource[F, A]
  ): WritableConfigSource[F, A, K] =
    new WritableConfigSource[F, A, K] {
      private val combined = ConfigSource.combined(writable, other)

      override def mergeConfig(a: A): F[A] = writable.mergeConfig(a)
      override def setConfig(a: A): F[Unit] = writable.setConfig(a)
      override def getConfig: F[A] = combined.getConfig
      override def deleteItem(key: K): F[A] = writable.deleteItem(key)
    }
}
