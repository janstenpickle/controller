package io.janstenpickle.controller.configsource.extruder

import java.time.DayOfWeek

import cats.Eq
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.instances.int._
import cats.derived.auto.eq._
import cats.instances.string._
import cats.syntax.either._
import eu.timepit.refined.cats._
import com.typesafe.config.{Config => TConfig}
import extruder.cats.effect.EffectValidation
import extruder.core.{Decoder, Encoder, Parser, Settings, Show}
import extruder.refined._
import extruder.typesafe.IntermediateTypes.Config
import extruder.typesafe.instances._
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.extruder.ExtruderConfigSource.PollingConfig
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.schedule.model._
import natchez.Trace

object ExtruderScheduleConfigSource {
  implicit val dayOfWeekShow: Show[DayOfWeek] = Show[Int].contramap(_.getValue)
  implicit val dayOfWeekParser: Parser[DayOfWeek] =
    Parser[Int].flatMapResult(i => Either.catchNonFatal(DayOfWeek.of(i)).leftMap(_.getMessage))

  def apply[F[_]: Sync: Trace, G[_]: Concurrent: Timer](
    config: ConfigFileSource[F],
    pollingConfig: PollingConfig,
    onUpdate: ConfigResult[String, Schedule] => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, WritableConfigSource[F, String, Schedule]] = {
    type EV[A] = EffectValidation[F, A]
    val decoder: Decoder[EV, Settings, ConfigResult[String, Schedule], TConfig] =
      Decoder[EV, Settings, ConfigResult[String, Schedule], TConfig]
    val encoder: Encoder[F, Settings, ConfigResult[String, Schedule], Config] =
      Encoder[F, Settings, ConfigResult[String, Schedule], Config]

    ExtruderConfigSource
      .polling[F, G, String, Schedule]("schedule", pollingConfig, config, onUpdate, decoder, encoder)
  }
}
