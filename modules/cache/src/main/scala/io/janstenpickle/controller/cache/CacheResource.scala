package io.janstenpickle.controller.cache

import java.util.concurrent.TimeUnit

import cats.effect.{Async, Resource}
import cats.syntax.functor._
import io.janstenpickle.catseffect.CatsEffect._
import org.cache2k.Cache2kBuilder
import scalacache.CatsEffect.modes._
import scalacache.cache2k.Cache2kCache

import scala.concurrent.duration.FiniteDuration

object CacheResource {
  def apply[F[_], A](timeout: FiniteDuration, keyClass: Class[A])(implicit F: Async[F]): Resource[F, Cache2kCache[A]] =
    Resource.make(
      suspendErrors(
        Cache2kCache(
          Cache2kBuilder
            .of[String, A](classOf[String], keyClass)
            .expireAfterWrite(timeout.toMillis, TimeUnit.MILLISECONDS)
            .enableJmx(true)
            .build()
        )
      )
    )(c => F.suspend(c.close()).void)
}
