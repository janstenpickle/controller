package io.janstenpickle.controller.kodi.config

import cats.effect.Async
import cats.instances.list._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{Functor, MonadError, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.kodi.KodiRemoteControl._
import io.janstenpickle.controller.kodi.{Commands, KodiDevice, KodiDiscovery}
import io.janstenpickle.controller.model.Button.{RemoteIcon, RemoteLabel, SwitchIcon}
import io.janstenpickle.controller.model.{Button, Remote}
import io.janstenpickle.trace4cats.inject.Trace
import scalacache.Cache

object KodiRemoteConfigSource {
  def deviceToRemotes[F[_]](remoteName: NonEmptyString, activityName: NonEmptyString, device: KodiDevice[F])(
    implicit F: MonadError[F, Throwable]
  ): F[List[Remote]] = {

    def remoteIcon(
      device: NonEmptyString,
      command: NonEmptyString,
      icon: NonEmptyString,
      newRow: Boolean = false,
      colored: Boolean = false
    ) = RemoteIcon(remoteName, CommandSource, device, command, icon, Some(newRow), Some(colored), None, None)

    def remoteLabel(
      device: NonEmptyString,
      command: NonEmptyString,
      label: NonEmptyString,
      newRow: Boolean = false,
      colored: Boolean = false
    ) = RemoteLabel(remoteName, CommandSource, device, command, label, Some(newRow), Some(colored), None, None)

    def switchIcon(
      name: NonEmptyString,
      device: NonEmptyString,
      icon: NonEmptyString,
      isOn: Boolean,
      newRow: Boolean = false
    ): SwitchIcon =
      SwitchIcon(name, device, icon, isOn, Some(newRow), None, None, None)

    def mainTemplate(device: NonEmptyString, isPlaying: Boolean): List[Button] =
      List(
        remoteIcon(device, Commands.Up, NonEmptyString("keyboard_arrow_up"), newRow = true),
        remoteIcon(device, Commands.Left, NonEmptyString("keyboard_arrow_left")),
        remoteIcon(device, Commands.Select, NonEmptyString("done"), colored = true),
        remoteIcon(device, Commands.Right, NonEmptyString("keyboard_arrow_right")),
        remoteIcon(device, Commands.Down, NonEmptyString("keyboard_arrow_down"), newRow = true),
        remoteIcon(device, Commands.SeekBack, NonEmptyString("fast_rewind")),
        remoteIcon(device, Commands.Back, NonEmptyString("undo")),
        switchIcon(
          NonEmptyString.unsafeFrom(s"${device.value}_playpause"),
          device,
          if (isPlaying) NonEmptyString("pause") else NonEmptyString("play_arrow"),
          isPlaying
        ),
        remoteIcon(device, Commands.SeekForward, NonEmptyString("fast_forward")),
      )

    def secondaryTemplate(device: NonEmptyString, isMuted: Boolean): List[Button] = List(
      remoteLabel(device, Commands.ScanVideoLibrary, NonEmptyString("Scan"), colored = true),
      remoteLabel(device, Commands.Subtitles, NonEmptyString("Subs"), colored = true),
      remoteLabel(device, Commands.OSD, NonEmptyString("OSD"), colored = true),
      switchIcon(
        NonEmptyString.unsafeFrom(s"${device.value}_mute"),
        device,
        if (isMuted) NonEmptyString("volume_up") else NonEmptyString("volume_off"),
        isMuted,
        newRow = true
      ),
      remoteIcon(device, Commands.VolDown, NonEmptyString("volume_down")),
      remoteIcon(device, Commands.VolUp, NonEmptyString("volume_up"))
    )

    for {
      playing <- device.isPlaying
      muted <- device.isMuted
      metadata <- device.playerDetails
    } yield
      List(
        Remote(
          NonEmptyString.unsafeFrom(s"kodi-${remoteName.value.toLowerCase}-1"),
          remoteName,
          mainTemplate(device.name, playing),
          Set(activityName),
          List(device.room),
          None,
          metadata
        ),
        Remote(
          NonEmptyString.unsafeFrom(s"kodi-${remoteName.value.toLowerCase}-2"),
          remoteName,
          secondaryTemplate(device.name, muted),
          Set(activityName),
          List(device.room),
          None,
          metadata
        )
      )
  }

  def apply[F[_]: Parallel: Trace](
    remoteName: NonEmptyString,
    activityName: NonEmptyString,
    discovery: KodiDiscovery[F],
    cache: Cache[F, ConfigResult[NonEmptyString, Remote]]
  )(implicit F: Async[F]): ConfigSource[F, NonEmptyString, Remote] =
    TracedConfigSource(
      new ConfigSource[F, NonEmptyString, Remote] {
        override def getConfig: F[ConfigResult[NonEmptyString, Remote]] =
          cache.cachingForMemoizeF(s"${remoteName.value}_remotes")(None)(
            discovery.devices
              .flatMap(_.devices.values.toList.parFlatTraverse(deviceToRemotes(remoteName, activityName, _)))
              .map(
                remotes =>
                  ConfigResult(
                    remotes
                      .sortBy(_.name.value)
                      .map { r =>
                        r.name -> r
                      }
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
