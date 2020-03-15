package io.janstenpickle.controller.events.kafka.events

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Sync, Timer}
import cats.instances.option._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import fs2.kafka.{ConsumerSettings, Deserializer, ProducerSettings, Serializer}
import io.circe.Codec
import io.circe.generic.extras.auto._
import io.circe.refined._
import io.circe.generic.extras.semiauto._
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.events.kafka.KafkaPubSub
import io.janstenpickle.controller.events.kafka.events.Serialization._
import io.janstenpickle.controller.model.RemoteCommand
import io.janstenpickle.controller.model.event.RemoteEvent
import io.janstenpickle.controller.model.event.RemoteEvent.{
  RemoteAddedEvent,
  RemoteLearnCommand,
  RemoteRemovedEvent,
  RemoteSendCommandEvent
}

object KafkaRemoteEventPubSub {
  implicit val remoteEventCodec: Codec.AsObject[RemoteEvent] = deriveConfiguredCodec[RemoteEvent]

  implicit def remoteEventKeySerializer[F[_]: Sync]: Serializer[F, RemoteEvent] =
    Serializer.string.contramap[RemoteEvent] {
      case e @ RemoteAddedEvent(_, _) => deriveConfiguredCodec[RemoteAddedEvent].apply(e).noSpacesSortKeys
      case e @ RemoteRemovedEvent(_, _) => deriveConfiguredCodec[RemoteRemovedEvent].apply(e).noSpacesSortKeys
      case RemoteLearnCommand(remoteName, remoteDevice, command) => s"learn-$remoteName-$remoteDevice-$command"
      case RemoteSendCommandEvent(RemoteCommand(remote, source, device, name)) => s"send-$remote-$source-$device-$name"
    }

  implicit def remoteEventKeyDeserializer[F[_]: Sync]: Deserializer[F, Option[RemoteEvent]] =
    optKeyDeserializer[F, RemoteRemovedEvent].map(_.widen)

  def apply[F[_]: ConcurrentEffect: ContextShift: Timer](
    topic: NonEmptyString,
    config: KafkaPubSub.Config
  ): Resource[F, EventPubSub[F, RemoteEvent]] =
    KafkaPubSub[F, RemoteEvent](
      topic,
      config,
      ProducerSettings(remoteEventKeySerializer[F], circeSerializer[F, RemoteEvent]),
      ConsumerSettings(optKeyDeserializer[F, RemoteEvent], circeDeserializer[F, RemoteEvent])
    )
}
