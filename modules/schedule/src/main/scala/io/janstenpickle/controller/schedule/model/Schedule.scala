package io.janstenpickle.controller.schedule.model

import cats.data.NonEmptyList
import io.janstenpickle.controller.model.Command

case class Schedule(time: Time, commands: NonEmptyList[Command])
