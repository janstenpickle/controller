package io.janstenpickle.controller.configsource.circe

import java.time.DayOfWeek

import cats.Applicative
import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.string._
import cats.syntax.either._
import extruder.core._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.schedule.model._
import natchez.Trace

object CirceScheduleConfigSource {
  implicit val dayOfWeekShow: Show[DayOfWeek] = Show[Int].contramap(_.getValue)
  implicit val dayOfWeekParser: Parser[DayOfWeek] =
    Parser[Int].flatMapResult(i => Either.catchNonFatal(DayOfWeek.of(i)).leftMap(_.getMessage))

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](config: ConfigFileSource[F], pollingConfig: PollingConfig)(
    implicit liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, WritableConfigSource[F, String, Schedule]] =
    CirceConfigSource
      .polling[F, G, String, Schedule]("schedule", pollingConfig, config, _ => Applicative[F].unit)
}
