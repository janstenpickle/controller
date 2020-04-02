package io.janstenpickle.controller.kafka

import cats.effect.{Resource, Sync}
import net.manub.embeddedkafka.{EmbeddedK, EmbeddedKafka, EmbeddedKafkaConfig}
import cats.syntax.functor._

object EmbeddedKafkaServer {
  case class Config(topics: List[String])

  def apply[F[_]: Sync](config: Config): Resource[F, EmbeddedK] = {
    implicit val kafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig.defaultConfig

    Resource
      .make(Sync[F].delay(EmbeddedKafka.start()))(kafka => Sync[F].delay(kafka.stop(false)))
      .evalMap { kafka =>
        Sync[F]
          .delay(config.topics.foreach { topic =>
            EmbeddedKafka.createCustomTopic(topic, Map("log.cleanup.policy" -> "compact"))
          })
          .as(kafka)
      }
  }
}
