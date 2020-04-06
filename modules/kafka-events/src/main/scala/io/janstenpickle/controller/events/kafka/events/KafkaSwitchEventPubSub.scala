package io.janstenpickle.controller.events.kafka.events

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, Deserializer, ProducerSettings, Serializer}
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._
import io.circe.refined._
import io.circe.generic.extras.semiauto._
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.events.kafka.KafkaPubSub
import io.janstenpickle.controller.events.kafka.events.Serialization._
import io.janstenpickle.controller.model.event.SwitchEvent

object KafkaSwitchEventPubSub {
  implicit val config: Configuration = Configuration.default.withDiscriminator("type")

  implicit val switchEventCodec: Codec.AsObject[SwitchEvent] = deriveConfiguredCodec[SwitchEvent]

  val switchRemovedEventCodec: Codec.AsObject[SwitchEvent.SwitchRemovedEvent] =
    deriveConfiguredCodec[SwitchEvent.SwitchRemovedEvent]

  def switchEventKeySerializer[F[_]: Sync]: Serializer[F, SwitchEvent] =
    jsonSerializer.contramap[SwitchEvent] {
      case SwitchEvent.SwitchAddedEvent(key, _) => switchRemovedEventCodec(SwitchEvent.SwitchRemovedEvent(key))
      case SwitchEvent.SwitchStateUpdateEvent(key, _, _) => switchRemovedEventCodec(SwitchEvent.SwitchRemovedEvent(key))
      case e @ SwitchEvent.SwitchRemovedEvent(_) => switchRemovedEventCodec(e)
    }

  def apply[F[_]: ConcurrentEffect: ContextShift: Timer](
    topic: NonEmptyString,
    config: KafkaPubSub.Config
  ): Resource[F, EventPubSub[F, SwitchEvent]] =
    KafkaPubSub[F, SwitchEvent](
      topic,
      config,
      ProducerSettings(switchEventKeySerializer[F], circeSerializer[F, SwitchEvent]),
      ConsumerSettings(Deserializer.const(Option.empty[SwitchEvent]), circeDeserializer[F, SwitchEvent])
        .withAutoOffsetReset(AutoOffsetReset.Earliest)
    )
}
