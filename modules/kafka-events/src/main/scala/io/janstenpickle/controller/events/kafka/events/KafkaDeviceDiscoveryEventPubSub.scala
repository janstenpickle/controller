package io.janstenpickle.controller.events.kafka.events

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.instances.option._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.kafka.{AutoOffsetReset, ConsumerSettings, ProducerSettings, Serializer}
import io.circe.generic.extras.Configuration
import io.circe.{Codec, Decoder}
import io.circe.generic.extras.auto._
import io.circe.generic.extras.semiauto._
import io.circe.refined._
import io.circe.syntax._
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.events.kafka.KafkaPubSub
import io.janstenpickle.controller.events.kafka.events.Serialization._
import io.janstenpickle.controller.model.event.DeviceDiscoveryEvent
import io.janstenpickle.controller.model.event.DeviceDiscoveryEvent._

object KafkaDeviceDiscoveryEventPubSub {
  implicit val config: Configuration = Configuration.default.withDiscriminator("type")

  implicit val deviceDiscoveryEventCodec: Codec.AsObject[DeviceDiscoveryEvent] =
    deriveConfiguredCodec[DeviceDiscoveryEvent]
  implicit val deviceRemovedDecoder: Decoder[DeviceRemoved] = deriveConfiguredDecoder[DeviceRemoved]

  def deviceDiscoveryEventSerializer[F[_]: Sync]: Serializer[F, DeviceDiscoveryEvent] = jsonSerializer.contramap {
    case DeviceDiscovered(key, _) => DeviceRemoved(key).asJson
    case e @ UnmappedDiscovered(_) => e.asJson
    case DeviceRename(key, _) => DeviceRemoved(key).asJson
    case e @ DeviceRemoved(_) => e.asJson
  }

  def apply[F[_]: ConcurrentEffect: ContextShift: Timer](
    topic: NonEmptyString,
    config: KafkaPubSub.Config
  ): Resource[F, EventPubSub[F, DeviceDiscoveryEvent]] =
    KafkaPubSub[F, DeviceDiscoveryEvent](
      topic,
      config,
      ProducerSettings(deviceDiscoveryEventSerializer[F], circeSerializer[F, DeviceDiscoveryEvent]),
      ConsumerSettings(
        optKeyDeserializer[F, DeviceRemoved].map(_.widen[DeviceDiscoveryEvent]),
        circeDeserializer[F, DeviceDiscoveryEvent]
      ).withAutoOffsetReset(AutoOffsetReset.Earliest)
    )
}
