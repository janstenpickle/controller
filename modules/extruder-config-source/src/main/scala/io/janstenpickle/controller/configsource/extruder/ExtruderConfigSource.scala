package io.janstenpickle.controller.configsource.extruder

import cats.Eq
import cats.effect._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.numeric.PosInt
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.core.{Decoder, Settings}
import extruder.typesafe._
import io.circe.Json
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.SetErrors
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.poller.{DataPoller, Empty}
import natchez.Trace

import scala.concurrent.duration._

object ExtruderConfigSource {
  case class PollingConfig(pollInterval: FiniteDuration = 10.seconds)

  private def make[F[_], A](configFile: ConfigFileSource[F])(
    implicit F: Sync[F],
    decoder: Decoder[EffectValidation[F, *], (Settings, CirceSettings), A, (TConfig, Json)],
    trace: Trace[F],
    setErrors: SetErrors[A]
  ): A => F[A] = {
    type EV[B] = EffectValidation[F, B]
    current =>
      trace.span("loadConfigs") {
        configFile.configs.flatMap { configs =>
          trace.span("decodeConfig") {
            decodeF[EV, A]
              .combine(extruder.circe.datasource)((configs.typesafe, configs.json))
              .map(setErrors.setErrors(_)(configs.error.map(_.getMessage).toList))
              .value
              .flatMap {
                case Left(errors) =>
                  trace
                    .put(
                      "error" -> true,
                      "error.count" -> errors.size,
                      "error.messages" -> errors.map(_.message).toList.mkString(",")
                    )
                    .as(setErrors.setErrors(current)(errors.map(_.message).toList))
                case Right(a) => F.pure(a)
              }
          }
        }
      }
  }

  def polling[F[_]: Trace, G[_]: Concurrent: Timer, A: Empty: Eq](
    name: String,
    config: PollingConfig,
    configFileSource: ConfigFileSource[F],
    onUpdate: A => F[Unit],
    decoder: Decoder[EffectValidation[F, *], (Settings, CirceSettings), A, (TConfig, Json)]
  )(
    implicit F: Sync[F],
    setErrors: SetErrors[A],
    liftLower: ContextualLiftLower[G, F, String]
  ): Resource[F, ConfigSource[F, A]] = {
    implicit val d: Decoder[EffectValidation[F, *], (Settings, CirceSettings), A, (TConfig, Json)] = decoder
    val source = make[F, A](configFileSource)

    DataPoller.traced[F, G, A, ConfigSource[F, A]](name)(
      (data: Data[A]) => source(data.value),
      config.pollInterval,
      PosInt(1),
      (data: Data[A], th: Throwable) => F.pure(setErrors.setErrors(data.value)(List(th.getMessage))),
      onUpdate
    ) { (getData, _) =>
      TracedConfigSource(new ConfigSource[F, A] {
        override def getConfig: F[A] = getData()
      }, name, "extruder")
    }
  }

}
