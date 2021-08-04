package io.janstenpickle.controller.mqtt

import java.util.UUID

import cats.effect.{Async, Resource}
import cats.syntax.flatMap._
import cats.effect.syntax.spawn._
import cats.syntax.functor._
import cats.~>
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt5.datatypes.{Mqtt5UserProperties, Mqtt5UserProperty}
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import com.hivemq.client.mqtt.mqtt5.message.subscribe.{Mqtt5Subscribe, Mqtt5Subscription}
import fs2.interop.reactivestreams._
import fs2.{Pipe, Stream}
import io.janstenpickle.controller.mqtt.Fs2MqttClient.MqttMessage
import io.reactivex.Flowable

import scala.compat.java8.FutureConverters._
import scala.concurrent.duration._

import scala.jdk.CollectionConverters._

trait Fs2MqttClient[F[_]] { outer =>
  def subscribe(topic: String): Stream[F, MqttMessage]
  def publish: Pipe[F, MqttMessage, Unit]
  def mapK[G[_]](fk: F ~> G, gk: G ~> F): Fs2MqttClient[G] = new Fs2MqttClient[G] {
    override def subscribe(topic: String): Stream[G, MqttMessage] = outer.subscribe(topic).translate(fk)
    override def publish: Pipe[G, MqttMessage, Unit] =
      stream => outer.publish(stream.translate(gk)).translate(fk)
  }
}

object Fs2MqttClient {
  case class Config(host: String, port: Int)
  case class MqttMessage(payload: Array[Byte], topic: String, properties: Map[String, String])

  def apply[F[_]](config: Config)(implicit F: Async[F]): Resource[F, Fs2MqttClient[F]] =
    for {
      client <- Resource
        .make(for {
          client <- F.delay(
            MqttClient
              .builder()
              .identifier(UUID.randomUUID().toString)
              .serverHost(config.host)
              .serverPort(config.port)
              .useMqttVersion5()
              .buildAsync()
          )
          _ <- Async[F].fromFuture(F.delay(client.connect().toScala))
        } yield client)(client => Async[F].fromFuture(F.delay(client.disconnect().toScala)).void)
      _ <- Stream
        .awakeEvery[F](10.seconds)
        .evalMap(_ => Async[F].fromFuture(F.delay(client.connect().toScala)))
        .compile
        .drain
        .background
    } yield {
      val rxClient = client.toRx

      new Fs2MqttClient[F] {
        override def subscribe(topic: String): Stream[F, MqttMessage] =
          rxClient
            .subscribePublishes(
              Mqtt5Subscribe.builder().addSubscription(Mqtt5Subscription.builder().topicFilter(topic).build()).build()
            )
            .toStream[F]
            .map { msg =>
              MqttMessage(
                msg.getPayloadAsBytes,
                msg.getTopic.toString,
                msg.getUserProperties
                  .asList()
                  .asScala
                  .map { prop =>
                    prop.getName.toString -> prop.getValue.toString
                  }
                  .toMap
              )
            }

        override def publish: Pipe[F, MqttMessage, Unit] =
          stream =>
            Stream
              .resource(stream.map { msg =>
                Mqtt5Publish
                  .builder()
                  .topic(msg.topic)
                  .payload(msg.payload)
                  .userProperties(Mqtt5UserProperties.of(msg.properties.map {
                    case (k, v) => Mqtt5UserProperty.of(k, v)
                  }.toSeq: _*))
                  .build()
              }.toUnicastPublisher)
              .flatMap { pub =>
                rxClient
                  .publish(Flowable.fromPublisher[Mqtt5Publish](pub))
                  .toStream[F]
                  .evalMap { result =>
                    val error = result.getError
                    if (error.isEmpty) F.unit
                    else F.raiseError[Unit](error.get())
                  }
            }
      }
    }
}
