package io.janstenpickle.controller.api.environment

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.events.EventPubSub
import io.janstenpickle.controller.events.kafka.KafkaPubSub
import io.janstenpickle.controller.events.kafka.events.{
  KafkaActivityEventPubSub,
  KafkaCommandEventPubSub,
  KafkaConfigEventPubSub,
  KafkaDeviceDiscoveryEventPubSub,
  KafkaMacroEventPubSub,
  KafkaRemoteEventPubSub,
  KafkaSwitchEventPubSub
}
import io.janstenpickle.controller.kafka.EmbeddedKafkaServer
import io.janstenpickle.controller.model.event.{
  ActivityUpdateEvent,
  CommandEvent,
  ConfigEvent,
  DeviceDiscoveryEvent,
  MacroEvent,
  RemoteEvent,
  SwitchEvent
}
import eu.timepit.refined.refineMV

object KafkaEvents {
  case class Topics(
    activityTopic: NonEmptyString = refineMV("activity"),
    configTopic: NonEmptyString = refineMV("config"),
    switchTopic: NonEmptyString = refineMV("switch"),
    remoteTopic: NonEmptyString = refineMV("remote"),
    macroTopic: NonEmptyString = refineMV("macro"),
    commandTopic: NonEmptyString = refineMV("command"),
    discoveryTopic: NonEmptyString = refineMV("discovery")
  )

  case class Config(kafka: KafkaPubSub.Config, topics: Topics)

  def apply[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](config: Config): Resource[F, Events[F]] =
    Parallel
      .parMap7[Resource[F, *], EventPubSub[F, RemoteEvent], EventPubSub[F, SwitchEvent], EventPubSub[F, ConfigEvent], EventPubSub[
        F,
        DeviceDiscoveryEvent
      ], EventPubSub[F, ActivityUpdateEvent], EventPubSub[F, MacroEvent], EventPubSub[F, CommandEvent], Events[F]](
        KafkaRemoteEventPubSub[F](config.topics.remoteTopic, config.kafka),
        KafkaSwitchEventPubSub[F](config.topics.switchTopic, config.kafka),
        KafkaConfigEventPubSub[F](config.topics.configTopic, config.kafka),
        KafkaDeviceDiscoveryEventPubSub[F](config.topics.discoveryTopic, config.kafka),
        KafkaActivityEventPubSub[F](config.topics.activityTopic, config.kafka),
        KafkaMacroEventPubSub[F](config.topics.macroTopic, config.kafka),
        KafkaCommandEventPubSub[F](config.topics.commandTopic, config.kafka),
      )(Events[F])

  def embedded[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel]: Resource[F, Events[F]] = {
    val topics = Topics()
    EmbeddedKafkaServer[F](
      EmbeddedKafkaServer
        .Config(
          List(
            topics.activityTopic,
            topics.configTopic,
            topics.switchTopic,
            topics.remoteTopic,
            topics.macroTopic,
            topics.commandTopic,
            topics.discoveryTopic
          ).map(_.value)
        )
    ).flatMap { kafka =>
      apply[F](
        Config(
          KafkaPubSub.Config(NonEmptyList.one(NonEmptyString.unsafeFrom(s"localhost:${kafka.config.kafkaPort}"))),
          topics
        )
      )
    }
  }
}
