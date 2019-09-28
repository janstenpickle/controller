package io.janstenpickle.controller.sonos.config

import cats.Applicative
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.model.{Activity, ContextButtonMapping}
import io.janstenpickle.controller.sonos.{Commands, SonosDiscovery}
import natchez.Trace

object SonosActivityConfigSource {

  case class Config(
    name: NonEmptyString = NonEmptyString("sonos"),
    label: NonEmptyString = NonEmptyString("Sonos"),
    remoteName: NonEmptyString = NonEmptyString("sonos"),
    combinedDeviceName: NonEmptyString = NonEmptyString("sonos"),
    playPauseMappingName: NonEmptyString = Commands.PlayPause,
    nextMappingName: NonEmptyString = Commands.Next,
    previousMappingName: NonEmptyString = Commands.Previous
  )

  def apply[F[_]: Applicative: Trace](config: Config, discovery: SonosDiscovery[F]): ConfigSource[F, String, Activity] =
    TracedConfigSource(
      new ConfigSource[F, String, Activity] {
        override def getConfig: F[ConfigResult[String, Activity]] =
          discovery.devices.map(
            devices =>
              ConfigResult(devices.map {
                case (name, _) =>
                  s"${name.value}-${config.name}" -> Activity(
                    name = config.name,
                    label = config.label,
                    contextButtons = List(
                      ContextButtonMapping
                        .Remote(config.playPauseMappingName, config.remoteName, name, Commands.PlayPause),
                      ContextButtonMapping
                        .Remote(config.nextMappingName, config.remoteName, name, Commands.Next),
                      ContextButtonMapping
                        .Remote(config.previousMappingName, config.remoteName, name, Commands.Previous)
                    ),
                    None,
                    name
                  )
              }, List.empty)
          )

        override def getValue(key: String): F[Option[Activity]] = getConfig.map(_.values.get(key))
      },
      "activities",
      "sonos"
    )
}
