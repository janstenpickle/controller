package io.janstenpickle.controller.events.kafka.events

import java.util.UUID

import cats.ApplicativeError
import cats.effect.{Clock, Sync}
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import fs2.kafka.{Deserializer, Serializer}
import io.circe.generic.extras.Configuration
import io.circe.parser.parse
import io.circe.refined._
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json}
import io.janstenpickle.controller.model.State

object Serialization {
  implicit val config: Configuration = Configuration.default.withDiscriminator("type")

  implicit val stateDecoder: Decoder[State] = Decoder.decodeBoolean.map(State.fromBoolean)
  implicit val stateEncoder: Encoder[State] = Encoder.encodeBoolean.contramap(_.isOn)

  implicit val throwableCodec: Codec[Option[Throwable]] =
    Codec.from(Decoder.const(None), Encoder.instance(_ => Json.Null))

  def optKeyDeserializer[F[_]: Sync, A: Decoder]: Deserializer[F, Option[A]] =
    Deserializer.instance { (_, _, data) =>
      Sync[F].delay(new String(data)).map { s =>
        parse(s).flatMap(Decoder[A].decodeJson).toOption
      }
    }

  def jsonSerializer[F[_]: Sync]: Serializer[F, Json] = Serializer.string.contramap(_.noSpacesSortKeys)

  def randomKeySerializer[F[_]: Sync, A]: Serializer[F, A] =
    Serializer.instance((_, _, _) => Sync[F].delay(UUID.randomUUID().toString.getBytes()))

  def circeDeserializer[F[_]: Sync: Clock, A: Decoder]: Deserializer[F, Option[A]] =
    Deserializer.instance { (topic, _, data) =>
      for {
        s <- Sync[F].delay(new String(data))
        a <- ApplicativeError[F, Throwable]
          .fromEither(
            parse(s)
              .flatMap(Decoder[A].decodeJson)
              .leftMap(err => new RuntimeException(s"${err.getMessage}\n$topic\n$s"))
          )
      } yield a

    }.option

  def circeSerializer[F[_]: Sync, A: Encoder]: Serializer[F, Option[A]] =
    Serializer.string[F].option.contramap(_.map(_.asJson.noSpacesSortKeys))
}
