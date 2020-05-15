package io.janstenpickle.controller.events.kafka.events

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, Deserializer, ProducerSettings}
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._
import io.circe.generic.extras.semiauto._
import io.circe.refined._
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.events.kafka.KafkaPubSub
import io.janstenpickle.controller.events.kafka.events.Serialization._
import io.janstenpickle.controller.model.event.ActivityUpdateEvent

object KafkaActivityEventPubSub {
  implicit val config: Configuration = Configuration.default.withDiscriminator("type")

  implicit val activityUpdateEventCodec: Codec.AsObject[ActivityUpdateEvent] =
    deriveConfiguredCodec[ActivityUpdateEvent]

  def apply[F[_]: ConcurrentEffect: ContextShift: Timer](
    topic: NonEmptyString,
    config: KafkaPubSub.Config
  ): Resource[F, EventPubSub[F, ActivityUpdateEvent]] =
    KafkaPubSub[F, ActivityUpdateEvent](
      topic,
      config,
      ProducerSettings(randomKeySerializer[F, ActivityUpdateEvent], circeSerializer[F, ActivityUpdateEvent]),
      ConsumerSettings(Deserializer.const(Option.empty[ActivityUpdateEvent]), circeDeserializer[F, ActivityUpdateEvent])
        .withAutoOffsetReset(AutoOffsetReset.Earliest)
    )
}
