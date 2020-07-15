package io.janstenpickle.controller.event.config

import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Functor}
import fs2.Stream
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.cache.{Cache, CacheResource}
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.events.syntax.stream._
import io.janstenpickle.trace4cats.inject.Trace

import scala.concurrent.duration._

object EventDrivenConfigSource {
  def apply[F[_]: Concurrent: Timer, G[_], A, K, V](
    subscriber: EventSubscriber[F, A],
    name: String,
    source: String,
    cacheTimeout: FiniteDuration
  )(pf: PartialFunction[A, Cache[F, K, V] => F[Unit]])(
    implicit trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, ConfigSource[F, K, V]] = {
    def listen(state: Cache[F, K, V]) =
      subscriber.filterEvent(_.source != source).subscribeEvent.evalMapTrace("config.receive") { a =>
        pf.lift(a).fold(Applicative[F].unit)(_.apply(state))
      }

    def listener(state: Cache[F, K, V]) =
      Stream.retry(listen(state).compile.drain, 5.seconds, _ + 1.second, Int.MaxValue).compile.drain.background

    for {
      state <- CacheResource.caffeine[F, K, V](cacheTimeout)
      _ <- listener(state)
    } yield
      TracedConfigSource(new ConfigSource[F, K, V] {
        override def functor: Functor[F] = Functor[F]
        override def getValue(key: K): F[Option[V]] = state.get(key)
        override def getConfig: F[ConfigResult[K, V]] = state.getAll.map { values =>
          new ConfigResult[K, V](values)
        }
      }, name, "event")
  }
}
