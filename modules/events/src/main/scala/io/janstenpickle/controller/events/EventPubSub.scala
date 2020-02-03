package io.janstenpickle.controller.events

import cats.effect.{Clock, Concurrent, Resource}
import cats.syntax.functor._
import fs2.concurrent.Topic

trait EventPubSub[F[_], A] { outer =>
  def publisher: EventPublisher[F, A]
  def subscriberResource: Resource[F, EventSubscriber[F, A]]
  def subscriberStream: EventSubscriber[F, A]
}

object EventPubSub {

  def topicNonBlocking[F[_]: Concurrent: Clock, A](maxQueued: Int): F[EventPubSub[F, A]] =
    Topic[F, Option[Event[A]]](None).map { topic =>
      new EventPubSub[F, A] {
        override val publisher: EventPublisher[F, A] = EventPublisher.fromTopic(topic)
        override def subscriberResource: Resource[F, EventSubscriber[F, A]] =
          EventSubscriber.resourceFromTopicNonBlocking(topic, maxQueued)

        override def subscriberStream: EventSubscriber[F, A] =
          EventSubscriber.streamFromTopicNonBlocking(topic, maxQueued)
      }
    }
}
