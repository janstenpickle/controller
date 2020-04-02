package io.janstenpickle.controller.events.kafka

import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.{ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, ProducerSettings}
import io.janstenpickle.controller.events.{EventPubSub, EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.event.ToOption

object KafkaPubSub {
  case class Config(bootstrapServers: NonEmptyList[NonEmptyString])

  def apply[F[_]: ConcurrentEffect: ContextShift: Timer, V: ToOption](
    topic: NonEmptyString,
    config: Config,
    producerSettings: ProducerSettings[F, V, Option[V]],
    consumerSettings: ConsumerSettings[F, Option[V], Option[V]]
  ): Resource[F, EventPubSub[F, V]] = {
    val bootstrapServers = config.bootstrapServers.toList.mkString(",")
    val consumer = Sync[F].delay(UUID.randomUUID.toString).map { groupId =>
      consumerSettings
        .withBootstrapServers(bootstrapServers)
        .withAllowAutoCreateTopics(true)
        .withGroupId(groupId)
        .withAutoOffsetReset(AutoOffsetReset.Earliest)
    }

    KafkaPublisher(topic, producerSettings.withBootstrapServers(bootstrapServers)).map { pub =>
      new EventPubSub[F, V] {
        override def publisher: EventPublisher[F, V] = pub
        override def subscriberResource: Resource[F, EventSubscriber[F, V]] =
          KafkaSubscriber(topic, consumer)
        override def subscriberStream: EventSubscriber[F, V] =
          KafkaSubscriber.stream(topic, consumer)
      }
    }
  }
}
