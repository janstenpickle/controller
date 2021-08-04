package io.janstenpickle.controller.events

import cats.Functor
import cats.effect.Clock
import cats.syntax.functor._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}
import org.typelevel.ci.CIString

import java.time.Instant

case class Event[A](value: A, time: Instant, source: String, headers: Map[CIString, String] = Map.empty) {
  def map[B](f: A => B): Event[B] = copy(value = f(value))

  override def equals(obj: Any): Boolean = obj match {
    case evt: Event[A] => evt.value == value
    case _: Any => false
  }

  override def hashCode(): Int = value.hashCode()
}

object Event {
  implicit val circeConfig: Configuration = Configuration.default.withDefaults

  implicit val ciStringEncoder: KeyEncoder[CIString] = KeyEncoder.encodeKeyString.contramap(_.toString)
  implicit val ciStringDecoder: KeyDecoder[CIString] = KeyDecoder.decodeKeyString.map(CIString(_))

  implicit def eventEncoder[A: Encoder]: Encoder[Event[A]] = deriveConfiguredEncoder
  implicit def eventDecoder[A: Decoder]: Decoder[Event[A]] = deriveConfiguredDecoder

  def apply[F[_]: Functor: Clock, A](value: A, source: String): F[Event[A]] = apply(value, source, Map.empty)

  def apply[F[_]: Functor: Clock, A](value: A, source: String, headers: Map[CIString, String]): F[Event[A]] =
    Clock[F].realTimeInstant.map { time =>
      new Event(value, time, source, headers)
    }
}
