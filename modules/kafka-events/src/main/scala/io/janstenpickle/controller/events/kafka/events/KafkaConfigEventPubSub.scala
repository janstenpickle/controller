package io.janstenpickle.controller.events.kafka.events

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, ProducerSettings, Serializer}
import io.circe.generic.extras.Configuration
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.extras.auto._
import io.circe.refined._
import io.circe.generic.extras.semiauto._
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.events.kafka.KafkaPubSub
import io.janstenpickle.controller.events.kafka.events.Serialization._
import io.janstenpickle.controller.model.event.ConfigEvent

object KafkaConfigEventPubSub {
  implicit val config: Configuration = Configuration.default.withDiscriminator("type")

  implicit val configEventCodec: Codec.AsObject[ConfigEvent] = deriveConfiguredCodec[ConfigEvent]

  def configEventKeySerializer[F[_]: Sync]: Serializer[F, ConfigEvent] =
    jsonSerializer.contramap[ConfigEvent] {
      case ConfigEvent.MacroAddedEvent(name, _, source) =>
        configEventCodec(ConfigEvent.MacroRemovedEvent(name, source))
      case e @ ConfigEvent.MacroRemovedEvent(_, _) =>
        configEventCodec(e)
      case ConfigEvent.ActivityAddedEvent(activity, source) =>
        configEventCodec(ConfigEvent.ActivityRemovedEvent(activity.room, activity.name, source))
      case e @ ConfigEvent.ActivityRemovedEvent(_, _, _) => configEventCodec(e)
      case ConfigEvent.ButtonAddedEvent(button, source) =>
        configEventCodec(ConfigEvent.ButtonRemovedEvent(button.name, button.room, source))
      case e @ ConfigEvent.ButtonRemovedEvent(_, _, _) => configEventCodec(e)
      case ConfigEvent.VirtualSwitchAddedEvent(key, _, source) =>
        configEventCodec(ConfigEvent.VirtualSwitchRemovedEvent(key, source))
      case e @ ConfigEvent.VirtualSwitchRemovedEvent(_, _) => configEventCodec(e)
      case ConfigEvent.RemoteAddedEvent(remote, source) =>
        configEventCodec(ConfigEvent.RemoteRemovedEvent(remote.name, source))
      case e @ ConfigEvent.RemoteRemovedEvent(_, _) => configEventCodec(e)
      case ConfigEvent.MultiSwitchAddedEvent(multiSwitch, source) =>
        configEventCodec(ConfigEvent.MultiSwitchRemovedEvent(multiSwitch.name, source))
      case e @ ConfigEvent.MultiSwitchRemovedEvent(_, _) => configEventCodec(e)
    }

  def apply[F[_]: ConcurrentEffect: ContextShift: Timer](
    topic: NonEmptyString,
    config: KafkaPubSub.Config
  ): Resource[F, EventPubSub[F, ConfigEvent]] =
    KafkaPubSub[F, ConfigEvent](
      topic,
      config,
      ProducerSettings(configEventKeySerializer[F], circeSerializer[F, ConfigEvent]),
      ConsumerSettings(optKeyDeserializer[F, ConfigEvent], circeDeserializer[F, ConfigEvent])
        .withAutoOffsetReset(AutoOffsetReset.Earliest)
    )
}
