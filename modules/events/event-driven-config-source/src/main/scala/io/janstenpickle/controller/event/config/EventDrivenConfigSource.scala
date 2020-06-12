package io.janstenpickle.controller.event.config

import cats.Functor
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Resource, Timer}
import io.janstenpickle.controller.events.EventSubscriber

import scala.concurrent.duration._
import fs2.Stream
import cats.effect.syntax.concurrent._
import cats.syntax.functor._
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}

object EventDrivenConfigSource {
  def apply[F[_]: Concurrent: Timer, A, K, V](
    subscriber: EventSubscriber[F, A]
  )(pf: PartialFunction[A, Map[K, V] => Map[K, V]]): Resource[F, ConfigSource[F, K, V]] = {
    def listen(state: Ref[F, Map[K, V]]) = subscriber.subscribe.evalMap { a =>
      state.update { map =>
        pf.lift(a).fold(map)(_.apply(map))
      }
    }

    def listener(state: Ref[F, Map[K, V]]) =
      Stream.retry(listen(state).compile.drain, 5.seconds, _ + 1.second, Int.MaxValue).compile.drain.background

    for {
      state <- Resource.liftF(Ref.of[F, Map[K, V]](Map.empty))
      _ <- listener(state)
    } yield
      new ConfigSource[F, K, V] {
        override def functor: Functor[F] = Functor[F]
        override def getValue(key: K): F[Option[V]] = state.get.map(_.get(key))
        override def getConfig: F[ConfigResult[K, V]] = state.get.map { values =>
          new ConfigResult[K, V](values)
        }
      }
  }
}
