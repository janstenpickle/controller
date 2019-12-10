package io.janstenpickle.controller.tplink.config

import cats.syntax.functor._
import cats.{Applicative, Functor}
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.model.Activity
import io.janstenpickle.controller.tplink.TplinkDiscovery
import natchez.Trace

object TplinkActivityConfigSource {
  def apply[F[_]: Applicative: Trace](discovery: TplinkDiscovery[F]): ConfigSource[F, String, Activity] =
    TracedConfigSource(
      new ConfigSource[F, String, Activity] {
        override def getConfig: F[ConfigResult[String, Activity]] =
          discovery.devices.map(
            devices =>
              ConfigResult(devices.devices.flatMap {
                case (_, dev) =>
                  dev.room.map { room =>
                    dev.roomName.value -> Activity(
                      name = dev.roomName,
                      label = dev.name,
                      contextButtons = List.empty,
                      None,
                      None,
                      room
                    )
                  }
              }, List.empty)
          )
        override def getValue(key: String): F[Option[Activity]] = getConfig.map(_.values.get(key))
        override def functor: Functor[F] = Functor[F]
      },
      "activities",
      "tplink"
    )
}
