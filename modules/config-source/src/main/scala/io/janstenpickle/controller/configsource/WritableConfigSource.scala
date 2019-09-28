package io.janstenpickle.controller.configsource

import cats.kernel.Semigroup
import cats.{Monad, Parallel}

trait WritableConfigSource[F[_], K, V] extends ConfigSource[F, K, V] {
  def setConfig(a: Map[K, V]): F[Unit]
  def mergeConfig(a: Map[K, V]): F[ConfigResult[K, V]]
  def deleteItem(key: K): F[ConfigResult[K, V]]
  def upsert(key: K, value: V): F[ConfigResult[K, V]]
}

object WritableConfigSource {
  def combined[F[_]: Monad: Parallel, A: Semigroup, K, V](
    writable: WritableConfigSource[F, K, V],
    other: ConfigSource[F, K, V]
  ): WritableConfigSource[F, K, V] =
    new WritableConfigSource[F, K, V] {
      private val combined = ConfigSource.combined(writable, other)

      override def mergeConfig(a: Map[K, V]): F[ConfigResult[K, V]] = writable.mergeConfig(a)
      override def setConfig(a: Map[K, V]): F[Unit] = writable.setConfig(a)
      override def getConfig: F[ConfigResult[K, V]] = combined.getConfig
      override def deleteItem(key: K): F[ConfigResult[K, V]] = writable.deleteItem(key)
      override def upsert(key: K, value: V): F[ConfigResult[K, V]] = writable.upsert(key, value)
      override def getValue(key: K): F[Option[V]] = combined.getValue(key)
    }
}
