package io.janstenpickle.controller.cache

import cats.effect.{Async, Resource, Sync}
import cats.syntax.applicativeError._
import cats.syntax.functor._
import com.github.benmanes.caffeine.cache.Caffeine
import scalacache.caffeine.CaffeineCache
import scalacache.{Entry, Cache => ScalaCache}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

object CacheResource {
  def apply[F[_], A](timeout: FiniteDuration, valueClass: Class[A])(
    implicit F: Async[F]
  ): Resource[F, ScalaCache[F, A]] = {
    val underlyingCaffeineCache =
      F.delay(Caffeine.newBuilder().expireAfterWrite(timeout.toMillis, TimeUnit.MILLISECONDS).build[String, Entry[A]])

    Resource.make(underlyingCaffeineCache)(cache => F.delay(cache.cleanUp())).map(cache => CaffeineCache(cache))
  }

  def caffeine[F[_], K, V](timeout: FiniteDuration)(implicit F: Sync[F]): Resource[F, Cache[F, K, V]] = {
    val underlyingCaffeineCache =
      F.delay(Caffeine.newBuilder().expireAfterWrite(timeout.toMillis, TimeUnit.MILLISECONDS).build[K, V]())

    Resource.make(underlyingCaffeineCache)(cache => F.delay(cache.cleanUp())).map { ccache =>
      new Cache[F, K, V] {

        override def get(key: K): F[Option[V]] = F.delay(Option(ccache.getIfPresent(key))).recover {
          case _: NullPointerException => None
        }

        override def set(key: K, value: V): F[Unit] = F.delay(ccache.put(key, value)).void

        override def remove(key: K): F[Unit] = F.delay(ccache.invalidate(key))

        override def getAll: F[Map[K, V]] = F.delay(ccache.asMap().asScala.toMap)
      }

    }
  }
//    def apply[F[_], A, B](timeout: FiniteDuration, keyClass: Class[A], valueClass: Class[B])(
//      implicit F: Async[F]
//    ): Resource[F, scalacache.caffeine.CaffeineCache[A, B]] =
//      Resource.make(
//        F.delay(
//          Cache2kBuilder
//            .of[A, B](keyClass, valueClass)
//            .expireAfterWrite(timeout.toMillis, TimeUnit.MILLISECONDS)
//            .enableJmx(true)
//            .build()
//        )
//      )(c => F.delay(c.close()))

//    apply[F, String, A](timeout, classOf[String], valueClass).map(Cache2kCache(_))

//  def apply[F[_], A, B](timeout: FiniteDuration, keyClass: Class[A], valueClass: Class[B])(
//    implicit F: Async[F]
//  ): Resource[F, scalacache.caffeine.CaffeineCache[A, B]] =
//    Resource.make(
//      F.delay(
//        Cache2kBuilder
//          .of[A, B](keyClass, valueClass)
//          .expireAfterWrite(timeout.toMillis, TimeUnit.MILLISECONDS)
//          .enableJmx(true)
//          .build()
//      )
//    )(c => F.delay(c.close()))
}
