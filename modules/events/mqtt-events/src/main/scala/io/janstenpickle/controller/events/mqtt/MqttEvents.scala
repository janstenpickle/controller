package io.janstenpickle.controller.events.mqtt

import cats.data.Reader
import cats.effect.{Concurrent, Resource}
import cats.effect.syntax.concurrent._
import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._
import io.circe.generic.extras.semiauto._
import io.circe.refined._
import io.circe.syntax._
import io.circe.parser.parse
import io.janstenpickle.controller.events.{Event, EventPubSub}
import io.janstenpickle.controller.model.event.{CommandEvent, ConfigEvent, RemoteEvent, SwitchEvent}
import io.janstenpickle.controller.mqtt.Fs2MqttClient
import fs2.Stream
import io.janstenpickle.controller.model.{State, SwitchType}
import io.janstenpickle.controller.mqtt.Fs2MqttClient.MqttMessage

object MqttEvents {
  case class Config(
    configTopic: String = "controller_config",
    switchTopic: String = "controller_switch",
    remoteTopic: String = "controller_remote",
    commandTopic: String = "controller_command"
  )

  implicit val config: Configuration = Configuration.default.withDiscriminator("type")
  implicit val stateEncoder: Encoder[State] = Encoder.encodeBoolean.contramap(_.isOn)
  implicit val switchTypeEncoder: Encoder[SwitchType] = Encoder.encodeString.contramap(_.toString.toLowerCase)
  implicit val errorEncoder: Encoder[Option[Throwable]] = Encoder.encodeBoolean.contramap(_.isDefined)
  implicit val switchEventEncoder: Encoder[SwitchEvent] = deriveConfiguredEncoder[SwitchEvent]
  implicit val configEventEncoder: Encoder[ConfigEvent] = deriveConfiguredEncoder[ConfigEvent]
  implicit val remoteEventEncoder: Encoder[RemoteEvent] = deriveConfiguredEncoder[RemoteEvent]

  def events[F[_], A: Encoder](topic: String, pubsub: EventPubSub[F, A]): Reader[Fs2MqttClient[F], Stream[F, Unit]] =
    Reader { mqtt =>
      pubsub.subscriberStream.subscribeEvent
        .map { event =>
          val eventBytes = event.asJson.noSpaces.getBytes("utf-8")
          MqttMessage(eventBytes, topic, Map.empty)
        }
        .through(mqtt.publish)
    }

  def commandEvents[F[_]](topic: String): Reader[(EventPubSub[F, CommandEvent], Fs2MqttClient[F]), Stream[F, Unit]] =
    Reader {
      case (pubSub, mqtt) =>
        mqtt
          .subscribe(topic)
          .map { message =>
            parse(new String(message.payload)).flatMap { json =>
              Decoder[Event[CommandEvent]]
                .map(_.value)
                .or(Decoder[CommandEvent])
                .decodeJson(json)
            }.toOption
          }
          .unNone
          .through(pubSub.publisher.pipe)
    }

  def apply[F[_]: Concurrent](
    config: Config,
    switchEventPubSub: EventPubSub[F, SwitchEvent],
    configEventPubSub: EventPubSub[F, ConfigEvent],
    remoteEventPubSub: EventPubSub[F, RemoteEvent],
    commandEventPubSub: EventPubSub[F, CommandEvent],
    mqtt: Fs2MqttClient[F]
  ): Resource[F, Unit] =
    for {
      _ <- (for {
        sw <- events(config.switchTopic, switchEventPubSub)
        conf <- events(config.configTopic, configEventPubSub)
        rem <- events(config.remoteTopic, remoteEventPubSub)
      } yield sw.merge(conf).merge(rem)).run(mqtt).compile.drain.background
      _ <- commandEvents(config.commandTopic)(commandEventPubSub, mqtt).compile.drain.background
    } yield ()
}
