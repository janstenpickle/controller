package io.janstenpickle.controller.events.kafka.events

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import fs2.kafka.{ConsumerSettings, Deserializer, ProducerSettings}
import io.circe.Codec
import io.circe.generic.extras.auto._
import io.circe.generic.extras.semiauto._
import io.circe.refined._
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.events.kafka.KafkaPubSub
import io.janstenpickle.controller.events.kafka.events.Serialization._
import io.janstenpickle.controller.model.event.CommandEvent

object KafkaCommandEventPubSub {
  implicit val commandEventCodec: Codec.AsObject[CommandEvent] = deriveConfiguredCodec[CommandEvent]

  def apply[F[_]: ConcurrentEffect: ContextShift: Timer](
    topic: NonEmptyString,
    config: KafkaPubSub.Config
  ): Resource[F, EventPubSub[F, CommandEvent]] =
    KafkaPubSub[F, CommandEvent](
      topic,
      config,
      ProducerSettings(randomKeySerializer[F, CommandEvent], circeSerializer[F, CommandEvent]),
      ConsumerSettings(Deserializer.const(Option.empty[CommandEvent]), circeDeserializer[F, CommandEvent])
    )
}
