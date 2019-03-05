package io.janstenpickle.controller.configsource.extruder

import cats.Eq
import cats.effect._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.numeric.PosInt
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.circe.yaml.instances._
import extruder.core.{Decoder, Settings}
import extruder.data.ValidationErrors
import extruder.typesafe._
import io.circe.Json
import io.janstenpickle.controller.poller.DataPoller.Data
import io.janstenpickle.controller.poller.{DataPoller, Empty}

import scala.concurrent.duration.FiniteDuration

object ExtruderConfigSource {
  private def make[F[_]: Sync, A](
    configFile: ConfigFileSource[F],
    pollError: (A, Option[Throwable]) => A,
    extruderError: (A, ValidationErrors) => A
  )(
    implicit decoder: Decoder[
      EffectValidation[F, ?],
      ((Settings, CirceSettings), CirceSettings),
      A,
      ((TConfig, Json), Json)
    ]
  ): A => F[A] = {
    type EV[X] = EffectValidation[F, X]
    current =>
      configFile.configs.flatMap { configs =>
        decodeF[EV, A]
          .combine(extruder.circe.yaml.datasource)
          .combine(extruder.circe.datasource)(((configs.typesafe, configs.yaml), configs.json))
          .map(pollError(_, configs.error))
          .value
          .map {
            case Left(errors) => extruderError(current, errors)
            case Right(a) => a
          }
      }
  }

  def apply[F[_]: Sync, A](
    configFileSource: ConfigFileSource[F],
    configSourceError: (A, Option[Throwable]) => A,
    extruderError: (A, ValidationErrors) => A
  )(
    implicit decoder: Decoder[
      EffectValidation[F, ?],
      ((Settings, CirceSettings), CirceSettings),
      A,
      ((TConfig, Json), Json)
    ],
    empty: Empty[A]
  ): () => F[A] =
    () => make(configFileSource, configSourceError, extruderError).apply(empty.empty)

  def polling[F[_]: Timer, A: Empty: Eq](
    pollInterval: FiniteDuration,
    configFileSource: ConfigFileSource[F],
    pollError: (A, Option[Throwable]) => A,
    extruderError: (A, ValidationErrors) => A,
    onUpdate: A => F[Unit]
  )(
    implicit F: Concurrent[F],
    decoder: Decoder[EffectValidation[F, ?], ((Settings, CirceSettings), CirceSettings), A, ((TConfig, Json), Json)]
  ): Resource[F, () => F[A]] = {
    val source = make[F, A](configFileSource, pollError, extruderError)

    DataPoller[F, A, () => F[A]](
      (data: Data[A]) => source(data.value),
      pollInterval,
      PosInt(1),
      (data: Data[A], th: Throwable) => F.pure(pollError(data.value, Some(th))),
      onUpdate
    ) { (getData, _) =>
      getData
    }
  }

}
