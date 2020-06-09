package io.janstenpickle.controller.configsource

import cats.kernel.{Monoid, Semigroup}
import cats.{Applicative, Functor, Monad, Parallel}
import cats.syntax.functor._
import cats.syntax.flatMap._

trait ConfigSource[F[_], K, V] {
  implicit def functor: Functor[F]

  def getValue(key: K): F[Option[V]]
  def getConfig: F[ConfigResult[K, V]]
  def listKeys: F[Set[K]] = getConfig.map(_.values.keySet)
}

object ConfigSource { self =>
  def empty[F[_], K, V](implicit F: Applicative[F]): ConfigSource[F, K, V] = new ConfigSource[F, K, V] {
    override def functor: Functor[F] = F
    override def getValue(key: K): F[Option[V]] = F.pure(None)
    override def getConfig: F[ConfigResult[K, V]] = F.pure(ConfigResult[K, V]())
  }

  def combined[F[_]: Parallel, K, V](
    x: ConfigSource[F, K, V],
    y: ConfigSource[F, K, V]
  )(implicit F: Monad[F], semigroup: Semigroup[ConfigResult[K, V]]): ConfigSource[F, K, V] =
    new ConfigSource[F, K, V] {
      override def getConfig: F[ConfigResult[K, V]] =
        Parallel.parMap2(x.getConfig, y.getConfig)(semigroup.combine)

      override def getValue(key: K): F[Option[V]] =
        x.getValue(key).flatMap {
          case Some(v) => F.pure(Some(v))
          case None => y.getValue(key)
        }

      override def listKeys: F[Set[K]] = Parallel.parMap2(x.listKeys, y.listKeys) { _ ++ _ }

      override def functor: Functor[F] = Functor[F]
    }

  implicit def configSourceMonoid[F[_]: Monad: Parallel, K, V](
    implicit semigroup: Semigroup[ConfigResult[K, V]]
  ): Monoid[ConfigSource[F, K, V]] =
    new Monoid[ConfigSource[F, K, V]] {
      override def empty: ConfigSource[F, K, V] = self.empty[F, K, V]
      override def combine(x: ConfigSource[F, K, V], y: ConfigSource[F, K, V]): ConfigSource[F, K, V] =
        self.combined(x, y)
    }
}
