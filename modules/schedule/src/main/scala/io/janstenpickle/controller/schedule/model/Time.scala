package io.janstenpickle.controller.schedule.model

import cats.Eq
import cats.instances.int._
import cats.instances.tuple._
import eu.timepit.refined.cats._
import eu.timepit.refined.types.time.{Hour, Minute}
import io.circe.Codec
import io.janstenpickle.controller.schedule.model._
import io.circe.generic.semiauto._
import io.circe.refined._

case class Time(days: Days, hourOfDay: Hour, minuteOfHour: Minute)

object Time {
  implicit val timeEq: Eq[Time] = Eq.by(time => (time.days, time.hourOfDay, time.minuteOfHour))

  implicit val timeCodec: Codec.AsObject[Time] = deriveCodec
}
