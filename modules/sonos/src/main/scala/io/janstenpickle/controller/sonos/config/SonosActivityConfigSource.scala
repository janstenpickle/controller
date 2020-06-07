package io.janstenpickle.controller.sonos.config

import cats.{Applicative, Functor}
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.model.{Activity, ContextButtonMapping}
import io.janstenpickle.controller.sonos.{CommandSource, Commands, SonosDiscovery}
import natchez.Trace

object SonosActivityConfigSource {

  case class Config(
    name: NonEmptyString = NonEmptyString("sonos"),
    label: NonEmptyString = NonEmptyString("Sonos"),
    remoteName: NonEmptyString = NonEmptyString("sonos"),
    combinedDeviceName: NonEmptyString = NonEmptyString("sonos"),
    playPauseMappingName: NonEmptyString = Commands.PlayPause,
    nextMappingName: NonEmptyString = Commands.Next,
    previousMappingName: NonEmptyString = Commands.Previous,
    volUpMappingName: NonEmptyString = Commands.VolUp,
    volDownMappingName: NonEmptyString = Commands.VolDown,
  )

  def apply[F[_]: Applicative: Trace](config: Config, discovery: SonosDiscovery[F]): ConfigSource[F, String, Activity] =
    TracedConfigSource(
      new ConfigSource[F, String, Activity] {
        override def getConfig: F[ConfigResult[String, Activity]] =
          discovery.devices.map(
            devices =>
              ConfigResult(devices.devices.map {
                case (name, _) =>
                  s"${name.value}-${config.name}" -> Activity(
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
                      ContextButtonMapping
                        .Remote(config.nextMappingName, config.remoteName, CommandSource, name, Commands.Next),
                      ContextButtonMapping
                        .Remote(config.previousMappingName, config.remoteName, CommandSource, name, Commands.Previous),
                      ContextButtonMapping
                        .Remote(config.volUpMappingName, config.remoteName, CommandSource, name, Commands.VolUp),
                      ContextButtonMapping
                        .Remote(config.volDownMappingName, config.remoteName, CommandSource, name, Commands.VolDown),
                    ),
                    None,
                    None,
                    name
                  )
              }, List.empty)
          )

        override def getValue(key: String): F[Option[Activity]] = getConfig.map(_.values.get(key))

        override def functor: Functor[F] = Functor[F]
      },
      "activities",
      "sonos"
    )
}
