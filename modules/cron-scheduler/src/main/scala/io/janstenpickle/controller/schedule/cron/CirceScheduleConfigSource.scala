package io.janstenpickle.controller.schedule.cron

import cats.Applicative
import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.string._
import cats.syntax.either._
import extruder.core._
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource
import io.janstenpickle.controller.configsource.circe.CirceConfigSource.PollingConfig
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.schedule.model._
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

import java.time.DayOfWeek

object CirceScheduleConfigSource {
  implicit val dayOfWeekShow: Show[DayOfWeek] = Show[Int].contramap(_.getValue)
  implicit val dayOfWeekParser: Parser[DayOfWeek] =
    Parser[Int].flatMapResult(i => Either.catchNonFatal(DayOfWeek.of(i)).leftMap(_.getMessage))

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[F, WritableConfigSource[F, String, Schedule]] =
    CirceConfigSource
      .polling[F, G, String, Schedule]("schedule", pollingConfig, config, _ => Applicative[F].unit, k)
}
