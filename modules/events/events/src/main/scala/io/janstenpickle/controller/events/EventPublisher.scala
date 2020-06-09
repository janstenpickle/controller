package io.janstenpickle.controller.events

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.effect.Clock
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, FlatMap}
import fs2.Pipe
import fs2.concurrent.Topic

trait EventPublisher[F[_], A] { outer =>
  def publish1(a: A): F[Unit]
  def pipe: Pipe[F, A, Unit]
  def narrow[B <: A]: EventPublisher[F, B] = contramap[B](x => x)
  def contramap[B](f: B => A): EventPublisher[F, B] = new EventPublisher[F, B] {
    override def publish1(a: B): F[Unit] = outer.publish1(f(a))
    override def pipe: Pipe[F, B, Unit] = _.map(f).through(outer.pipe)
  }
  def mapK[G[_]](fk: F ~> G, gk: G ~> F): EventPublisher[G, A] = EventPublisher.mapK(fk, gk)(this)
}

object EventPublisher {
  def fromTopic[F[_]: FlatMap, A](topic: Topic[F, Option[Event[A]]])(implicit clock: Clock[F]): EventPublisher[F, A] =
    new EventPublisher[F, A] { outer =>
      override def publish1(a: A): F[Unit] = clock.realTime(TimeUnit.MILLISECONDS).flatMap { millis =>
        topic.publish1(Some(Event(a, Instant.ofEpochMilli(millis))))
      }
      override def pipe: Pipe[F, A, Unit] =
        _.evalMap { a =>
          clock.realTime(TimeUnit.MILLISECONDS).map { millis =>
            Some(Event(a, Instant.ofEpochMilli(millis)))
          }
        }.through(topic.publish)
    }

  private def mapK[F[_], G[_], A](fk: F ~> G, gk: G ~> F)(publisher: EventPublisher[F, A]) = new EventPublisher[G, A] {
    override def publish1(a: A): G[Unit] = fk(publisher.publish1(a))

    override def pipe: Pipe[G, A, Unit] = st => publisher.pipe(st.translate(gk)).translate(fk)
  }
}
