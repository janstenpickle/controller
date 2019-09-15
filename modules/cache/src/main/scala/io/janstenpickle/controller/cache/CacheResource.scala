package io.janstenpickle.controller.cache

import java.util.concurrent.TimeUnit

import cats.effect.{Async, Resource}
import cats.syntax.functor._
import org.cache2k.{Cache, Cache2kBuilder}
import scalacache.CatsEffect.modes._
import scalacache.cache2k.Cache2kCache

import scala.concurrent.duration.FiniteDuration

object CacheResource {
  def apply[F[_], A](timeout: FiniteDuration, valueClass: Class[A])(
    implicit F: Async[F]
  ): Resource[F, Cache2kCache[A]] =
    apply[F, String, A](timeout, classOf[String], valueClass).map(Cache2kCache(_))

  def apply[F[_], A, B](timeout: FiniteDuration, keyClass: Class[A], valueClass: Class[B])(
    implicit F: Async[F]
  ): Resource[F, Cache[A, B]] =
    Resource.make(
      F.delay(
        Cache2kBuilder
          .of[A, B](keyClass, valueClass)
          .expireAfterWrite(timeout.toMillis, TimeUnit.MILLISECONDS)
          .enableJmx(true)
          .build()
      )
    )(c => F.delay(c.close()))
}
