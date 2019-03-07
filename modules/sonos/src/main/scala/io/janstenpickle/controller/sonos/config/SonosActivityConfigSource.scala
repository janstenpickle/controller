package io.janstenpickle.controller.sonos.config

import cats.Applicative
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.ActivityConfigSource
import io.janstenpickle.controller.model.{Activities, Activity, ContextButtonMapping}
import io.janstenpickle.controller.sonos.{Commands, SonosDiscovery}

object SonosActivityConfigSource {

  case class Config(
    name: NonEmptyString = NonEmptyString("sonos"),
    label: NonEmptyString = NonEmptyString("Sonos"),
    remoteName: NonEmptyString = NonEmptyString("sonos"),
    combinedDeviceName: NonEmptyString = NonEmptyString("sonos"),
    playPauseMappingName: NonEmptyString = NonEmptyString("play_pause"),
    nextMappingName: NonEmptyString = NonEmptyString("next"),
    previousMappingName: NonEmptyString = NonEmptyString("previous")
  )

  def apply[F[_]: Applicative](config: Config, discovery: SonosDiscovery[F]): ActivityConfigSource[F] =
    new ActivityConfigSource[F] {
      override def getActivities: F[Activities] =
        discovery.devices.map(
          devices =>
            Activities(devices.toList.collect {
              case (name, _) =>
                Activity(
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
            })
        )
    }
}