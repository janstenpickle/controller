package io.janstenpickle.controller.kodi.config

import cats.effect.Async
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{Functor, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.kodi.KodiRemoteControl._
import io.janstenpickle.controller.kodi.{Commands, KodiDiscovery}
import io.janstenpickle.controller.model.Button.{RemoteIcon, RemoteLabel, SwitchIcon}
import io.janstenpickle.controller.model.{Button, Remote}
import natchez.Trace
import scalacache.Cache
import scalacache.CatsEffect.modes._

object KodiRemoteConfigSource {
  def apply[F[_]: Parallel: Trace](
    remoteName: NonEmptyString,
    activityName: NonEmptyString,
    discovery: KodiDiscovery[F],
    cache: Cache[ConfigResult[NonEmptyString, Remote]]
  )(implicit F: Async[F]): ConfigSource[F, NonEmptyString, Remote] = {

    def template(device: NonEmptyString, isPlaying: Boolean): List[Button] =
      List(
        RemoteIcon(
          remoteName,
          CommandSource,
          device,
          Commands.Up,
          NonEmptyString("keyboard_arrow_up"),
          Some(true),
          None,
          None,
          None
        ),
        RemoteIcon(
          remoteName,
          CommandSource,
          device,
          Commands.Left,
          NonEmptyString("keyboard_arrow_left"),
          None,
          None,
          None,
          None
        ),
        RemoteIcon(
          remoteName,
          CommandSource,
          device,
          Commands.Select,
          NonEmptyString("done"),
          None,
          Some(true),
          None,
          None
        ),
        RemoteIcon(
          remoteName,
          CommandSource,
          device,
          Commands.Right,
          NonEmptyString("keyboard_arrow_right"),
          None,
          None,
          None,
          None
        ),
        RemoteIcon(
          remoteName,
          CommandSource,
          device,
          Commands.Down,
          NonEmptyString("keyboard_arrow_down"),
          Some(true),
          None,
          None,
          None
        ),
        RemoteIcon(remoteName, CommandSource, device, Commands.Back, NonEmptyString("undo"), None, None, None, None),
        SwitchIcon(
          NonEmptyString.unsafeFrom(s"${device.value}_playpause"),
          remoteName,
          if (isPlaying) NonEmptyString("pause") else NonEmptyString("play_arrow"),
          isPlaying,
          None,
          None,
          None,
          None
        ),
        RemoteLabel(
          remoteName,
          CommandSource,
          device,
          Commands.ScanVideoLibrary,
          NonEmptyString("Scan"),
          None,
          Some(true),
          None,
          None
        ),
        RemoteLabel(
          remoteName,
          CommandSource,
          device,
          Commands.Subtitles,
          NonEmptyString("Subs"),
          None,
          Some(true),
          None,
          None
        )
      )

    TracedConfigSource(
      new ConfigSource[F, NonEmptyString, Remote] {
        override def getConfig: F[ConfigResult[NonEmptyString, Remote]] =
          cache.cachingForMemoizeF(s"${remoteName.value}_remotes")(None)(
            discovery.devices
              .flatMap(_.values.toList.parTraverse { device =>
                for {
                  buttons <- device.isPlaying.map(template(device.name, _))
                  metadata <- device.playerDetails
                } yield
                  device.name ->
                    Remote(
                      NonEmptyString.unsafeFrom(s"kodi-${remoteName.value.toLowerCase}"),
                      remoteName,
                      buttons,
                      Set(activityName),
                      List(device.room),
                      None,
                      metadata
                    )

              })
              .map(
                remotes =>
                  ConfigResult(
                    remotes
                      .sortBy(_._1.value)
                      .toMap,
                    List.empty
                )
              )
          )

        override def getValue(key: NonEmptyString): F[Option[Remote]] = getConfig.map(_.values.get(key))

        override def functor: Functor[F] = F
      },
      "remotes",
      "kodi"
    )
  }
}
