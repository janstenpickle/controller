package io.janstenpickle.controller.events.kafka.events

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import cats.instances.list._
import cats.syntax.applicativeError._
import eu.timepit.refined.refineMV
import eu.timepit.refined.types.string.NonEmptyString
import fs2.kafka._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.events.kafka.KafkaPubSub
import io.janstenpickle.controller.events.{EventPubSub, Events}
import io.janstenpickle.controller.model.event._
import org.apache.kafka.clients.admin.NewTopic

import scala.jdk.CollectionConverters._

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
      .parMap8[Resource[F, *], String, EventPubSub[F, RemoteEvent], EventPubSub[F, SwitchEvent], EventPubSub[
        F,
        ConfigEvent
      ], EventPubSub[F, DeviceDiscoveryEvent], EventPubSub[F, ActivityUpdateEvent], EventPubSub[F, MacroEvent], EventPubSub[
        F,
        CommandEvent
      ], Events[F]](
        Resource.pure[F, String](""), // FIXME proper source
        KafkaRemoteEventPubSub[F](config.topics.remoteTopic, config.kafka),
        KafkaSwitchEventPubSub[F](config.topics.switchTopic, config.kafka),
        KafkaConfigEventPubSub[F](config.topics.configTopic, config.kafka),
        KafkaDeviceDiscoveryEventPubSub[F](config.topics.discoveryTopic, config.kafka),
        KafkaActivityEventPubSub[F](config.topics.activityTopic, config.kafka),
        KafkaMacroEventPubSub[F](config.topics.macroTopic, config.kafka),
        KafkaCommandEventPubSub[F](config.topics.commandTopic, config.kafka),
      )(Events[F])

  def create[F[_]: ConcurrentEffect: ContextShift: Timer: Parallel](
    instance: String,
    config: KafkaPubSub.Config
  ): Resource[F, Events[F]] = {
    val instanceHeader = "instance" -> instance
    val updatedConfig = config.copy(
      staticHeaders = config.staticHeaders + instanceHeader,
      headerNonMatchesFilter = config.headerNonMatchesFilter + instanceHeader
    )

    for {
      logger <- Resource.liftF(Slf4jLogger.create[F])
      admin <- adminClientResource(
        AdminClientSettings[F].withBootstrapServers(updatedConfig.bootstrapServers.toList.mkString(","))
      )
      topics = Topics()
      _ <- Resource.liftF(
        admin
          .createTopics(
            List(
              topics.activityTopic,
              topics.configTopic,
              topics.switchTopic,
              topics.remoteTopic,
              topics.macroTopic,
              topics.commandTopic,
              topics.discoveryTopic
            ).map { topic =>
              new NewTopic(topic.value, 1, 1.toShort)
                .configs(Map("cleanup.policy" -> "compact", "max.compaction.lag.ms" -> "900000").asJava)
            }
          )
          .handleError(th => logger.warn(th)("Failed to create Kafka topics"))
      )
      events <- apply[F](Config(updatedConfig, topics))
    } yield events
  }

}
