package io.janstenpickle.controller.sonos.config

import cats.Applicative
import cats.syntax.applicative._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.ActivityConfigSource
import io.janstenpickle.controller.model.{Activities, Activity, ContextButtonMapping}
import io.janstenpickle.controller.sonos.Commands

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

  def apply[F[_]: Applicative](config: Config): ActivityConfigSource[F] = new ActivityConfigSource[F] {
    override def getActivities: F[Activities] =
      Activities(
        List(
          Activity(
            name = config.name,
            label = config.label,
            contextButtons = List(
              ContextButtonMapping
                .Remote(config.playPauseMappingName, config.remoteName, config.combinedDeviceName, Commands.PlayPause),
              ContextButtonMapping
                .Remote(config.nextMappingName, config.remoteName, config.combinedDeviceName, Commands.Next),
              ContextButtonMapping
                .Remote(config.previousMappingName, config.remoteName, config.combinedDeviceName, Commands.Previous)
            ),
            None
          )
        ),
        List.empty
      ).pure[F]
  }
}
