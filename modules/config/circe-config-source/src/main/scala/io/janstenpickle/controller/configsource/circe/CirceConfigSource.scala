package io.janstenpickle.controller.configsource.circe

import cats.{Applicative, Eq, Functor}
import cats.data.Validated.{Invalid, Valid}
import cats.effect.{Concurrent, Resource, Sync, Timer}
import cats.kernel.Monoid
import extruder.cats.effect.EffectValidation
import io.chrisdavenport.log4cats.Logger
import io.circe.{Codec, Decoder, Encoder, KeyDecoder, KeyEncoder}
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.configsource.{ConfigResult, WritableConfigSource}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.extruder.ConfigFileSource
import io.janstenpickle.controller.model.{Activity, SetEditable}
import io.janstenpickle.controller.model.event.ConfigEvent
import natchez.Trace
import cats.syntax.flatMap._

import scala.concurrent.duration._
import cats.syntax.apply._
import cats.syntax.functor._
import eu.timepit.refined.types.numeric.PosInt
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.syntax._
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.poller.DataPoller
import io.janstenpickle.controller.poller.DataPoller.Data

import scala.collection.compat._

object CirceConfigSource {

  case class PollingConfig(pollInterval: FiniteDuration = 10.seconds)

  case class Diff[K, V](removed: List[(K, V)], added: List[(K, V)], updated: List[(K, V)])

  private def decode[F[_], K: KeyDecoder, V: Decoder](
    configFile: ConfigFileSource[F],
    logger: Logger[F]
  )(implicit F: Sync[F], trace: Trace[F]): ConfigResult[K, V] => F[ConfigResult[K, V]] =
    current =>
      trace.span("loadConfigs") {
        configFile.configs.flatMap { configs =>
          trace.span("decodeConfig") {
            Decoder[ConfigResult[K, V]].decodeAccumulating(configs.json.hcursor) match {
              case Invalid(errors) =>
                val errorString = errors
                  .map { err =>
                    s"${err.message} - ${err.history.mkString(", ")}"
                  }
                  .toList
                  .mkString("\n")
                logger.warn(s"Failed to decode configuration: $errorString") *> trace
                  .put("error" -> true, "error.count" -> errors.size, "error.messages" -> errorString)
                  .as(current.copy(errors = errors.map(_.message).toList))
              case Valid(result) =>
                Applicative[F].pure(ConfigResult[K, V](result.values, configs.error.map(_.getMessage).toList))
            }
          }
        }
    }

  private def encode[F[_], K: KeyEncoder, V: Encoder](
    configFile: ConfigFileSource[F],
  )(implicit F: Sync[F], trace: Trace[F]): ConfigResult[K, V] => F[Unit] =
    current =>
      trace
        .span("encodeConfig") {
          Applicative[F].pure(current.asJson)
        }
        .flatMap { ts =>
          trace.span("writeConfig") { configFile.write(ts) }
      }

  def polling[F[_]: Trace, G[_]: Concurrent: Timer, K: KeyDecoder: KeyEncoder, V: Decoder: Encoder: Eq](
    name: String,
    config: PollingConfig,
    configFileSource: ConfigFileSource[F],
    onUpdate: Diff[K, V] => F[Unit],
  )(
    implicit F: Sync[F],
    eq: Eq[ConfigResult[K, V]],
    liftLower: ContextualLiftLower[G, F, String],
    monoid: Monoid[ConfigResult[K, V]],
    setEditable: SetEditable[V]
  ): Resource[F, WritableConfigSource[F, K, V]] =
    Resource
      .liftF(Slf4jLogger.fromName[F](s"${name}ConfigSource"))
      .flatMap { implicit logger =>
        val source = decode[F, K, V](configFileSource, logger)
        val sink = encode[F, K, V](configFileSource)

        DataPoller.traced[F, G, ConfigResult[K, V], WritableConfigSource[F, K, V]](name, "type" -> "circe.config")(
          (data: Data[ConfigResult[K, V]]) => source(data.value),
          config.pollInterval,
          PosInt(1),
          (data: Data[ConfigResult[K, V]], th: Throwable) => F.pure(data.value.copy(errors = List(th.getMessage))),
          (o: ConfigResult[K, V], n: ConfigResult[K, V]) => {
            val oldList = o.values.toList
            val newList = n.values.toList
            val updatedList =
              o.values.filter { case (k, v) => n.values.contains(k) && Eq[V].neqv(v, n.values(k)) }.toList

            onUpdate(Diff(oldList.diff(newList), newList.diff(oldList), updatedList))
          }
        ) { (getData, update) =>
          TracedConfigSource.writable(
            new WritableConfigSource[F, K, V] {
              override def functor: Functor[F] = F

              override def getConfig: F[ConfigResult[K, V]] = getData().map { cr =>
                ConfigResult(cr.values.view.mapValues(setEditable.set(_)(editable = true)).toMap, cr.errors)
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
            "circe"
          )
        }
      }
      .flatMap { cs =>
        Resource
          .make(F.unit)(
            _ =>
              cs.getConfig.flatMap { result =>
                onUpdate(Diff(result.values.toList, List.empty, List.empty))
            }
          )
          .map(_ => cs)
      }
}
