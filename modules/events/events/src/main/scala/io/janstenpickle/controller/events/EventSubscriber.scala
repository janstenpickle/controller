package io.janstenpickle.controller.events

import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.effect.syntax.spawn._
import cats.effect.{Concurrent, Resource, Sync}
import cats.~>
import fs2.Stream
import fs2.concurrent.Topic
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait EventSubscriber[F[_], A] { outer =>
  def subscribe: Stream[F, A] = subscribeEvent.map(_.value)
  def subscribeEvent: Stream[F, Event[A]]
  def map[B](f: A => B): EventSubscriber[F, B] = new EventSubscriber[F, B] {
    override def subscribeEvent: Stream[F, Event[B]] = outer.subscribeEvent.map { event =>
      Event(f(event.value), event.time, event.source)
    }
  }
  def collect[B](pf: PartialFunction[A, B]): EventSubscriber[F, B] = new EventSubscriber[F, B] {
    override def subscribeEvent: Stream[F, Event[B]] =
      outer.subscribeEvent.map { event =>
        pf.andThen(Option(_))
          .applyOrElse(event.value, (_: A) => Option.empty[B])
          .map(Event(_, event.time, event.source))
      }.unNone
  }
  def filterEvent(f: Event[A] => Boolean): EventSubscriber[F, A] = new EventSubscriber[F, A] {
    override def subscribeEvent: Stream[F, Event[A]] = outer.subscribeEvent.filter(f)
  }
  def mapK[G[_]](fk: F ~> G): EventSubscriber[G, A] = EventSubscriber.mapK(fk)(this)
}

object EventSubscriber {
  private def nonBlockingQueueResource[F[_]: Async, A](
    topic: Topic[F, Option[Event[A]]],
    maxQueued: Int
  ): Resource[F, Queue[F, Event[A]]] =
    Resource.eval(Slf4jLogger.create[F]).flatMap { logger =>
      def loop(queue: Queue[F, Event[A]]): Stream[F, Unit] =
        topic.subscribe(maxQueued).unNone.evalMap(queue.offer).handleErrorWith { th =>
          Stream.eval(logger.warn(th)("Queue subscription failed, restarting")) >> loop(queue)
        }

      for {
        queue <- Resource.eval(Queue.circularBuffer[F, Event[A]](maxQueued))
        _ <- loop(queue).compile.drain.background
      } yield queue
    }
  private def blockingQueueResource[F[_]: Sync: Concurrent, A](
    topic: Topic[F, Option[Event[A]]],
    maxQueued: Int
  ): Resource[F, Queue[F, Event[A]]] =
    Resource.eval(Slf4jLogger.create[F]).flatMap { logger =>
      def loop(queue: Queue[F, Event[A]]): Stream[F, Unit] =
        topic.subscribe(maxQueued).unNone.evalMap(queue.offer).handleErrorWith { th =>
          Stream.eval(logger.warn(th)("Queue subscription failed, restarting")) >> loop(queue)
        }

      for {
        queue <- Resource.eval(Queue.bounded[F, Event[A]](maxQueued))
        _ <- loop(queue).compile.drain.background
      } yield queue
    }

  def resourceFromTopicNonBlocking[F[_]: Async, A](
    topic: Topic[F, Option[Event[A]]],
    maxQueued: Int
  ): Resource[F, EventSubscriber[F, A]] =
    nonBlockingQueueResource(topic, maxQueued).map { queue =>
      new EventSubscriber[F, A] {
        override def subscribeEvent: Stream[F, Event[A]] = Stream.fromQueueUnterminated(queue)
      }
    }

  def resourceFromTopicBlocking[F[_]: Async, A](
    topic: Topic[F, Option[Event[A]]],
    maxQueued: Int
  ): Resource[F, EventSubscriber[F, A]] =
    blockingQueueResource(topic, maxQueued).map { queue =>
      new EventSubscriber[F, A] {
        override def subscribeEvent: Stream[F, Event[A]] = Stream.fromQueueUnterminated(queue)
      }
    }

  def streamFromTopicNonBlocking[F[_]: Async, A](
    topic: Topic[F, Option[Event[A]]],
    maxQueued: Int
  ): EventSubscriber[F, A] =
    new EventSubscriber[F, A] {
      override def subscribeEvent: Stream[F, Event[A]] =
        Stream.resource(nonBlockingQueueResource(topic, maxQueued)).flatMap(Stream.fromQueueUnterminated(_))
    }

  def streamFromTopicBlocking[F[_]: Async, A](
    topic: Topic[F, Option[Event[A]]],
    maxQueued: Int
  ): EventSubscriber[F, A] =
    new EventSubscriber[F, A] {
      override def subscribeEvent: Stream[F, Event[A]] =
        Stream.resource(blockingQueueResource(topic, maxQueued)).flatMap(Stream.fromQueueUnterminated(_))
    }

  private def mapK[F[_], G[_], A](fk: F ~> G)(subscriber: EventSubscriber[F, A]): EventSubscriber[G, A] =
    new EventSubscriber[G, A] {
      override def subscribeEvent: Stream[G, Event[A]] = subscriber.subscribeEvent.translate(fk)
    }
}
