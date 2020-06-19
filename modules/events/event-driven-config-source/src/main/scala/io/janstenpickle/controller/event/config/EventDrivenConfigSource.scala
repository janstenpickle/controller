package io.janstenpickle.controller.event.config

import cats.effect.concurrent.Ref
import cats.effect.syntax.concurrent._
import cats.effect.{Concurrent, Resource, Timer}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Functor}
import fs2.Stream
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.events.EventSubscriber
import io.janstenpickle.controller.events.syntax.stream._
import natchez.Trace

import scala.concurrent.duration._

object EventDrivenConfigSource {
  def apply[F[_]: Concurrent: Timer, G[_], A, K, V](subscriber: EventSubscriber[F, A], name: String, source: String)(
    pf: PartialFunction[A, Map[K, V] => F[Map[K, V]]]
  )(
    implicit trace: Trace[F],
    liftLower: ContextualLiftLower[G, F, (String, Map[String, String])]
  ): Resource[F, ConfigSource[F, K, V]] = {
    def listen(state: Ref[F, Map[K, V]]) =
      subscriber.filterEvent(_.source != source).subscribeEvent.evalMapTrace("config.receive") { a =>
        val doUpdate: F[Boolean] = state.access.flatMap {
          case (map, update) =>
            pf.lift(a).fold(Applicative[F].pure(true))(_.apply(map).flatMap(update))
        }

        doUpdate.tailRecM(_.map(if (_) Right(()) else Left(doUpdate)))
      }

    def listener(state: Ref[F, Map[K, V]]) =
      Stream.retry(listen(state).compile.drain, 5.seconds, _ + 1.second, Int.MaxValue).compile.drain.background

    for {
      state <- Resource.liftF(Ref.of[F, Map[K, V]](Map.empty))
      _ <- listener(state)
    } yield
      TracedConfigSource(new ConfigSource[F, K, V] {
        override def functor: Functor[F] = Functor[F]
        override def getValue(key: K): F[Option[V]] = state.get.map(_.get(key))
        override def getConfig: F[ConfigResult[K, V]] = state.get.map { values =>
          new ConfigResult[K, V](values)
        }
      }, name, "event")
  }
}
