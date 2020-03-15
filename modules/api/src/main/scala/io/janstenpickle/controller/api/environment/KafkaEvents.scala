package io.janstenpickle.controller.api.environment

import cats.Parallel
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
import io.janstenpickle.controller.model.event.{
  ActivityUpdateEvent,
  CommandEvent,
  ConfigEvent,
  DeviceDiscoveryEvent,
  MacroEvent,
  RemoteEvent,
  SwitchEvent
}

object KafkaEvents {
  case class Config(
    kafka: KafkaPubSub.Config,
    activityTopic: NonEmptyString,
    configTopic: NonEmptyString,
    switchTopic: NonEmptyString,
    remoteTopic: NonEmptyString,
    macroTopic: NonEmptyString,
    commandTopic: NonEmptyString,
    discoveryTopic: NonEmptyString
  )

  def apply[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](config: Config): Resource[F, Events[F]] =
    Parallel
      .parMap7[Resource[F, *], EventPubSub[F, RemoteEvent], EventPubSub[F, SwitchEvent], EventPubSub[F, ConfigEvent], EventPubSub[
        F,
        DeviceDiscoveryEvent
      ], EventPubSub[F, ActivityUpdateEvent], EventPubSub[F, MacroEvent], EventPubSub[F, CommandEvent], Events[F]](
        KafkaRemoteEventPubSub[F](config.remoteTopic, config.kafka),
        KafkaSwitchEventPubSub[F](config.switchTopic, config.kafka),
        KafkaConfigEventPubSub[F](config.configTopic, config.kafka),
        KafkaDeviceDiscoveryEventPubSub[F](config.discoveryTopic, config.kafka),
        KafkaActivityEventPubSub[F](config.activityTopic, config.kafka),
        KafkaMacroEventPubSub[F](config.macroTopic, config.kafka),
        KafkaCommandEventPubSub[F](config.commandTopic, config.kafka),
      )(Events[F])
}
