package io.janstenpickle.controller.events

import cats.effect.{Concurrent, Resource}
import fs2.Stream
import fs2.concurrent.{Queue, Topic}
import cats.effect.syntax.concurrent._
import cats.~>

trait EventSubscriber[F[_], A] { outer =>
  def subscribe: Stream[F, A] = subscribeEvent.map(_.value)
  def subscribeEvent: Stream[F, Event[A]]
  def map[B](f: A => B): EventSubscriber[F, B] = new EventSubscriber[F, B] {
    override def subscribeEvent: Stream[F, Event[B]] = outer.subscribeEvent.map { event =>
      Event(f(event.value), event.time)
    }
  }
  def collect[B](pf: PartialFunction[A, B]): EventSubscriber[F, B] = new EventSubscriber[F, B] {
    override def subscribeEvent: Stream[F, Event[B]] =
      outer.subscribeEvent.map { event =>
        pf.andThen(Option(_)).applyOrElse(event.value, (_: A) => Option.empty[B]).map(Event(_, event.time))
      }.unNone
  }
  def mapK[G[_]](fk: F ~> G): EventSubscriber[G, A] = EventSubscriber.mapK(fk)(this)
}

object EventSubscriber {
  private def nonBlockingQueueResource[F[_]: Concurrent, A](topic: Topic[F, Option[Event[A]]], maxQueued: Int) =
    for {
      queue <- Resource.liftF(Queue.circularBuffer[F, Event[A]](maxQueued))
      _ <- Resource.make(topic.subscribe(maxQueued).unNone.through(queue.enqueue).compile.drain.start)(_.cancel)
    } yield queue

  def resourceFromTopicNonBlocking[F[_]: Concurrent, A](
    topic: Topic[F, Option[Event[A]]],
    maxQueued: Int
  ): Resource[F, EventSubscriber[F, A]] =
    nonBlockingQueueResource(topic, maxQueued).map { queue =>
      new EventSubscriber[F, A] {
        override def subscribeEvent: Stream[F, Event[A]] = queue.dequeue
      }
    }

  def streamFromTopicNonBlocking[F[_]: Concurrent, A](
    topic: Topic[F, Option[Event[A]]],
    maxQueued: Int
  ): EventSubscriber[F, A] =
    new EventSubscriber[F, A] {
      override def subscribeEvent: Stream[F, Event[A]] =
        Stream.resource(nonBlockingQueueResource(topic, maxQueued)).flatMap(_.dequeue)
    }

  private def mapK[F[_], G[_], A](fk: F ~> G)(subscriber: EventSubscriber[F, A]): EventSubscriber[G, A] =
    new EventSubscriber[G, A] {
      override def subscribeEvent: Stream[G, Event[A]] = subscriber.subscribeEvent.translate(fk)
    }
}
