package io.janstenpickle.controller.schedule

import java.time.DayOfWeek

import cats.{Eq, Order}
import cats.data.NonEmptySet
import cats.instances.int._
import io.circe.{Codec, Decoder, Encoder}

import scala.util.Try

package object model {
  type Days = NonEmptySet[DayOfWeek]

  implicit val dayOfWeekOrder: Order[DayOfWeek] = Order.by(_.getValue())
  implicit val dayOfWeekOrdering: Ordering[DayOfWeek] = dayOfWeekOrder.toOrdering
  implicit val dayOfWeekEq: Eq[DayOfWeek] = Eq.by(_.getValue)
  implicit val dayOfWeekDecoder: Decoder[DayOfWeek] = Decoder.decodeString.emapTry { value =>
    Try(DayOfWeek.valueOf(value))
  }
  implicit val dayOfWeekEncoder: Encoder[DayOfWeek] = Encoder.encodeString.contramap(_.name())
  implicit val dayOfWeekCodec: Codec[DayOfWeek] = Codec.from(dayOfWeekDecoder, dayOfWeekEncoder)
}
