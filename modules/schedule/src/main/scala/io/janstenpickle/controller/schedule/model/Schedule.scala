package io.janstenpickle.controller.schedule.model

import cats.Eq
import cats.data.NonEmptyList
import io.circe.Codec
import io.janstenpickle.controller.model.Command
import io.circe.generic.semiauto._

case class Schedule(time: Time, commands: NonEmptyList[Command])

object Schedule {
  implicit val scheduleCodec: Codec.AsObject[Schedule] = deriveCodec
  implicit val scheduleEq: Eq[Schedule] = cats.derived.semi.eq
}
