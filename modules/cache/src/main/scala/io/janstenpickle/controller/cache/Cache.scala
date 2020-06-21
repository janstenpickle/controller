package io.janstenpickle.controller.cache

trait Cache[F[_], K, V] {
  def get(key: K): F[Option[V]]
  def set(key: K, value: V): F[Unit]
  def remove(key: K): F[Unit]
  def getAll: F[Map[K, V]]
}
