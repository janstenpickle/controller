package io.janstenpickle.controller.events

import cats.effect.{Async, MonadCancelThrow, Resource}
import cats.syntax.functor._
import cats.~>
import fs2.concurrent.Topic
import io.janstenpickle.trace4cats.inject.Trace

trait EventPubSub[F[_], A] { outer =>
  def publisher: EventPublisher[F, A]
  def subscriberResource: Resource[F, EventSubscriber[F, A]]
  def subscriberStream: EventSubscriber[F, A]
  def mapK[G[_]: MonadCancelThrow](fk: F ~> G, gk: G ~> F)(implicit F: MonadCancelThrow[F]): EventPubSub[G, A] =
    EventPubSub.mapK(fk, gk)(this)
}

object EventPubSub {

  def topicNonBlocking[F[_]: Async: Trace, A](maxQueued: Int, source: String): F[EventPubSub[F, A]] =
    Topic[F, Option[Event[A]]].map { topic =>
      new EventPubSub[F, A] {
        override val publisher: EventPublisher[F, A] = EventPublisher.fromTopic(topic, source)
        override def subscriberResource: Resource[F, EventSubscriber[F, A]] =
          EventSubscriber.resourceFromTopicNonBlocking(topic, maxQueued)

        override def subscriberStream: EventSubscriber[F, A] =
          EventSubscriber.streamFromTopicNonBlocking(topic, maxQueued)
      }
    }

  def topicBlocking[F[_]: Async: Trace, A](maxQueued: Int, source: String): F[EventPubSub[F, A]] =
    Topic[F, Option[Event[A]]].map { topic =>
      new EventPubSub[F, A] {
        override val publisher: EventPublisher[F, A] = EventPublisher.fromTopic(topic, source)
        override def subscriberResource: Resource[F, EventSubscriber[F, A]] =
          EventSubscriber.resourceFromTopicBlocking(topic, maxQueued)

        override def subscriberStream: EventSubscriber[F, A] =
          EventSubscriber.streamFromTopicBlocking(topic, maxQueued)
      }
    }

  private def mapK[F[_]: MonadCancelThrow, G[_]: MonadCancelThrow, A](fk: F ~> G, gk: G ~> F)(
    pubSub: EventPubSub[F, A]
  ): EventPubSub[G, A] =
    new EventPubSub[G, A] {
      override def publisher: EventPublisher[G, A] = pubSub.publisher.imapK(fk, gk)
      override def subscriberResource: Resource[G, EventSubscriber[G, A]] =
        pubSub.subscriberResource.mapK(fk).map(_.mapK(fk))
      override def subscriberStream: EventSubscriber[G, A] = pubSub.subscriberStream.mapK(fk)
    }
}
