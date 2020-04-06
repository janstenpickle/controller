package io.janstenpickle.controller.events.kafka

import java.util.UUID

import cats.data.NonEmptyList
import cats.effect.{ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, ProducerSettings}
import io.janstenpickle.controller.events.{EventPubSub, EventPublisher, EventSubscriber}
import io.janstenpickle.controller.model.event.ToOption
import scala.concurrent.duration._

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
        .withGroupId(groupId)
        .withMaxPollInterval(10.minutes)
        .withMaxPollRecords(500)
        .withPollInterval(500.milliseconds)
        .withEnableAutoCommit(true)
        .withProperty("fetch.min.bytes", "5")
    }

    KafkaPublisher(
      topic,
      producerSettings.withBootstrapServers(bootstrapServers).withProperty("compression.type", "snappy")
    ).map { pub =>
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
