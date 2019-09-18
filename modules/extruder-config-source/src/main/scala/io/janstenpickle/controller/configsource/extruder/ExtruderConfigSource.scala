package io.janstenpickle.controller.configsource.extruder

import cats.Eq
import cats.effect._
import cats.kernel.Monoid
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.typesafe.config.{Config => TConfig}
import eu.timepit.refined.types.numeric.PosInt
import extruder.cats.effect.EffectValidation
import extruder.circe.CirceSettings
import extruder.core.{Decoder, Encoder, Settings}
import extruder.typesafe.IntermediateTypes.Config
import extruder.typesafe._
import io.circe.Json
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.SetErrors
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data
import natchez.Trace

import scala.concurrent.duration._

object ExtruderConfigSource {
  case class PollingConfig(pollInterval: FiniteDuration = 10.seconds)

  private def decode[F[_], A](
    configFile: ConfigFileSource[F],
    decoder: Decoder[EffectValidation[F, *], (Settings, CirceSettings), A, (TConfig, Json)],
  )(implicit F: Sync[F], trace: Trace[F], setErrors: SetErrors[A]): A => F[A] = {
    type EV[B] = EffectValidation[F, B]
    current =>
      implicit val d: Decoder[EffectValidation[F, *], (Settings, CirceSettings), A, (TConfig, Json)] = decoder

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

  private def encode[F[_], A](
    configFile: ConfigFileSource[F],
    encoder: Encoder[F, Settings, A, Config]
  )(implicit F: Sync[F], trace: Trace[F]): A => F[Unit] = { current =>
    implicit val e: Encoder[F, Settings, A, Config] = encoder

    trace
      .span("encodeConfig") {
        encodeF[F](current)
      }
      .flatMap { ts =>
        trace.span("writeConfig") { configFile.write(ts) }
      }

  }

  def polling[F[_]: Trace, G[_]: Concurrent: Timer, A: Eq, K](
    name: String,
    config: PollingConfig,
    configFileSource: ConfigFileSource[F],
    onUpdate: A => F[Unit],
    delete: (K, A) => A,
    decoder: Decoder[EffectValidation[F, *], (Settings, CirceSettings), A, (TConfig, Json)],
    encoder: Encoder[F, Settings, A, Config]
  )(
    implicit F: Sync[F],
    setErrors: SetErrors[A],
    liftLower: ContextualLiftLower[G, F, String],
    monoid: Monoid[A]
  ): Resource[F, WritableConfigSource[F, A, K]] = {
    val source = decode[F, A](configFileSource, decoder)
    val sink = encode[F, A](configFileSource, encoder)

    DataPoller.traced[F, G, A, WritableConfigSource[F, A, K]](name)(
      (data: Data[A]) => source(data.value),
      config.pollInterval,
      PosInt(1),
      (data: Data[A], th: Throwable) => F.pure(setErrors.setErrors(data.value)(List(th.getMessage))),
      onUpdate
    ) { (getData, update) =>
      TracedConfigSource.writable(new WritableConfigSource[F, A, K] {
        override def getConfig: F[A] = getData()
        override def setConfig(a: A): F[Unit] = sink(a) *> update(a)
        override def mergeConfig(a: A): F[A] = getData().flatMap { current =>
          val updated = monoid.combine(a, current)
          setConfig(updated).as(updated)
        }
        override def deleteItem(key: K): F[A] = getData().flatMap { a =>
          val updated = delete(key, a)
          setConfig(updated).as(updated)
        }
      }, name, "extruder")
    }
  }

}
