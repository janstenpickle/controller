package io.janstenpickle.controller.events.kafka.events

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import fs2.kafka.{ConsumerSettings, Deserializer, ProducerSettings}
import io.circe.{Codec, Decoder}
import io.circe.generic.extras.auto._
import io.circe.generic.extras.semiauto._
import io.circe.refined._
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.events.kafka.KafkaPubSub
import io.janstenpickle.controller.events.kafka.events.Serialization._
import io.janstenpickle.controller.model.event.MacroEvent

object KafkaMacroEventPubSub {
  implicit val macroEventCodec: Codec.AsObject[MacroEvent] = deriveConfiguredCodec[MacroEvent]

  def apply[F[_]: ConcurrentEffect: ContextShift: Timer](
    topic: NonEmptyString,
    config: KafkaPubSub.Config
  ): Resource[F, EventPubSub[F, MacroEvent]] =
    KafkaPubSub[F, MacroEvent](
      topic,
      config,
      ProducerSettings(randomKeySerializer[F, MacroEvent], circeSerializer[F, MacroEvent]),
      ConsumerSettings(Deserializer.const(Option.empty[MacroEvent]), circeDeserializer[F, MacroEvent])
    )
}
