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
import extruder.core.{Decoder, Encoder, Settings}
import extruder.typesafe.IntermediateTypes.Config
import extruder.typesafe._
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.SetEditable
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data
import natchez.Trace

import scala.concurrent.duration._

object ExtruderConfigSource {
  case class PollingConfig(pollInterval: FiniteDuration = 10.seconds)

  private def decode[F[_], K, V](
    configFile: ConfigFileSource[F],
    decoder: Decoder[EffectValidation[F, *], Settings, ConfigResult[K, V], TConfig],
    logger: Logger[F]
  )(implicit F: Sync[F], trace: Trace[F]): ConfigResult[K, V] => F[ConfigResult[K, V]] = {
    type EV[B] = EffectValidation[F, B]
    current =>
      implicit val d: Decoder[EffectValidation[F, *], Settings, ConfigResult[K, V], TConfig] =
        decoder

      trace.span("loadConfigs") {
        configFile.configs.flatMap { configs =>
          trace.span("decodeConfig") {
            decodeF[EV, ConfigResult[K, V]](configs.typesafe)
              .map(r => ConfigResult[K, V](r.values, configs.error.map(_.getMessage).toList))
              .value
              .flatMap {
                case Left(errors) =>
                  val errorString = errors.map(_.message).toList.mkString(",")
                  logger.warn(s"Failed to decode configuration: $errorString") *> trace
                    .put("error" -> true, "error.count" -> errors.size, "error.messages" -> errorString)
                    .as(current.copy(errors = errors.map(_.message).toList))
                case Right(a) => F.pure(a)
              }
          }
        }
      }
  }

  private def encode[F[_], K, V](
    configFile: ConfigFileSource[F],
    encoder: Encoder[F, Settings, ConfigResult[K, V], Config]
  )(implicit F: Sync[F], trace: Trace[F]): ConfigResult[K, V] => F[Unit] = { current =>
    implicit val e: Encoder[F, Settings, ConfigResult[K, V], Config] = encoder

    trace
      .span("encodeConfig") {
        encodeF[F](current)
      }
      .flatMap { ts =>
        trace.span("writeConfig") { configFile.write(ts) }
      }
  }

  def polling[F[_]: Trace, G[_]: Concurrent: Timer, K, V](
    name: String,
    config: PollingConfig,
    configFileSource: ConfigFileSource[F],
    onUpdate: ConfigResult[K, V] => F[Unit],
    decoder: Decoder[EffectValidation[F, *], Settings, ConfigResult[K, V], TConfig],
    encoder: Encoder[F, Settings, ConfigResult[K, V], Config]
  )(
    implicit F: Sync[F],
    eq: Eq[ConfigResult[K, V]],
    liftLower: ContextualLiftLower[G, F, String],
    monoid: Monoid[ConfigResult[K, V]],
    setEditable: SetEditable[V]
  ): Resource[F, WritableConfigSource[F, K, V]] =
    Resource.liftF(Slf4jLogger.fromName[F](s"${name}ConfigSource")).flatMap { logger =>
      val source = decode[F, K, V](configFileSource, decoder, logger)
      val sink = encode[F, K, V](configFileSource, encoder)

      DataPoller.traced[F, G, ConfigResult[K, V], WritableConfigSource[F, K, V]](name)(
        (data: Data[ConfigResult[K, V]]) => source(data.value),
        config.pollInterval,
        PosInt(1),
        (data: Data[ConfigResult[K, V]], th: Throwable) => F.pure(data.value.copy(errors = List(th.getMessage))),
        onUpdate
      ) { (getData, update) =>
        TracedConfigSource.writable(
          new WritableConfigSource[F, K, V] {
            override def getConfig: F[ConfigResult[K, V]] = getData().map { cr =>
              ConfigResult(cr.values.mapValues(setEditable.set(_)(editable = true)), cr.errors)
            }
            override def setConfig(a: Map[K, V]): F[Unit] = {
              val newConfig = ConfigResult(a, List.empty)
              sink(newConfig) *> update(newConfig)
            }
            override def mergeConfig(a: Map[K, V]): F[ConfigResult[K, V]] = getData().flatMap { current =>
              val updated = monoid.combine(ConfigResult(a, List.empty), current)
              sink(updated).as(updated)
            }
            override def deleteItem(key: K): F[ConfigResult[K, V]] = getData().flatMap { a =>
              val updated = a.copy(values = a.values - key)
              sink(updated).as(updated)
            }

            override def upsert(key: K, value: V): F[ConfigResult[K, V]] = getData().flatMap { a =>
              val updated = a.copy(values = a.values.updated(key, value))
              sink(updated) *> update(updated).as(updated)
            }

            override def getValue(key: K): F[Option[V]] = getData().map(_.values.get(key))
          },
          name,
          "extruder"
        )
      }
    }

}
