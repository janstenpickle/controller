package io.janstenpickle.controller.kodi.config

import cats.syntax.functor._
import cats.{Applicative, Functor}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.kodi.KodiRemoteControl._
import io.janstenpickle.controller.kodi.{Commands, KodiDiscovery}
import io.janstenpickle.controller.model.{Activity, ContextButtonMapping}
import natchez.Trace

object KodiActivityConfigSource {
  case class Config(
    name: NonEmptyString = NonEmptyString("kodi"),
    label: NonEmptyString = NonEmptyString("Kodi"),
    remoteName: NonEmptyString = NonEmptyString("kodi"),
    playPauseMappingName: NonEmptyString = NonEmptyString("play_pause")
  )

  def apply[F[_]: Applicative: Trace](config: Config, discovery: KodiDiscovery[F]): ConfigSource[F, String, Activity] =
    TracedConfigSource(
      new ConfigSource[F, String, Activity] {
        override def getConfig: F[ConfigResult[String, Activity]] =
          discovery.devices.map(
            devices =>
              ConfigResult(devices.devices.map {
                case (name, dev) =>
                  config.name.value -> Activity(
                    name = config.name,
                    label = config.label,
                    contextButtons = List(
                      ContextButtonMapping
                        .Remote(
                          config.playPauseMappingName,
                          config.remoteName,
                          CommandSource,
                          name,
                          Commands.PlayPause
                        ),
                    ),
                    None,
                    None,
                    dev.room
                  )
              }, List.empty)
          )
        override def getValue(key: String): F[Option[Activity]] = getConfig.map(_.values.get(key))
        override def functor: Functor[F] = Functor[F]
      },
      "activities",
      "kodi"
    )
}
