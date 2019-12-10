package io.janstenpickle.controller.sonos.config

import cats.effect.Async
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{Functor, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.config.trace.TracedConfigSource
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.model.Button.{RemoteIcon, SwitchIcon}
import io.janstenpickle.controller.model.{Button, Remote}
import io.janstenpickle.controller.sonos.{CommandSource, Commands, SonosDiscovery}
import natchez.Trace
import scalacache.Cache
import scalacache.CatsEffect.modes._

object SonosRemoteConfigSource {
  def apply[F[_]: Parallel: Trace](
    remoteName: NonEmptyString,
    activityName: NonEmptyString,
    allRooms: Boolean,
    discovery: SonosDiscovery[F],
    cache: Cache[ConfigResult[NonEmptyString, Remote]]
  )(implicit F: Async[F]): ConfigSource[F, NonEmptyString, Remote] = {
    def simpleTemplate(device: NonEmptyString, isMuted: Boolean): List[Button] =
      List(
        SwitchIcon(
          NonEmptyString.unsafeFrom(s"${device.value}_mute"),
          remoteName,
          NonEmptyString("volume_off"),
          isMuted,
          Some(true),
          None,
          None,
          None
        ),
        RemoteIcon(
          remoteName,
          CommandSource,
          device,
          Commands.VolDown,
          NonEmptyString("volume_down"),
          None,
          None,
          None,
          None
        ),
        RemoteIcon(
          remoteName,
          CommandSource,
          device,
          Commands.VolUp,
          NonEmptyString("volume_up"),
          None,
          None,
          None,
          None
        )
      )

    def groupTemplate(device: NonEmptyString, isController: Boolean, isGrouped: Boolean): List[Button] =
      if (isController && isGrouped) List.empty
      else
        List(
          SwitchIcon(
            NonEmptyString.unsafeFrom(s"${device.value}_group"),
            remoteName,
            NonEmptyString("speaker_group"),
            isGrouped,
            Some(true),
            None,
            None,
            None
          )
        )

    def template(device: NonEmptyString, isMuted: Boolean, isPlaying: Boolean): List[Button] =
      List(
        RemoteIcon(
          remoteName,
          CommandSource,
          device,
          Commands.Previous,
          NonEmptyString("fast_rewind"),
          None,
          None,
          None,
          None
        ),
        RemoteIcon(
          remoteName,
          CommandSource,
          device,
          Commands.PlayPause,
          if (isPlaying) NonEmptyString("pause") else NonEmptyString("play_arrow"),
          None,
          Some(isPlaying),
          None,
          None
        ),
        RemoteIcon(
          remoteName,
          CommandSource,
          device,
          Commands.Next,
          NonEmptyString("fast_forward"),
          None,
          None,
          None,
          None
        )
      ) ++ simpleTemplate(device, isMuted)

    TracedConfigSource(
      new ConfigSource[F, NonEmptyString, Remote] {
        override def getConfig: F[ConfigResult[NonEmptyString, Remote]] =
          cache.cachingForMemoizeF(s"${remoteName.value}_remotes")(None)(
            discovery.devices
              .flatMap(_.devices.values.toList.parTraverse {
                device =>
                  for {
                    isMuted <- device.isMuted
                    isController <- device.isController
                    isGrouped <- device.isGrouped
                    buttons <- if (isController)
                      device.isPlaying
                        .map(template(device.name, isMuted, _) ++ groupTemplate(device.name, isController, isGrouped))
                    else
                      F.pure(
                        simpleTemplate(device.name, isMuted) ++ groupTemplate(device.name, isController, isGrouped)
                      )
                    nowPlaying <- device.nowPlaying
                    remoteName <- nowPlaying match {
                      case None => F.pure(device.label)
                      case Some(np) =>
                        F.fromEither(
                          NonEmptyString
                            .from(s"${device.label} (${np.title} - ${np.artist})")
                            .leftMap(new RuntimeException(_))
                        )
                    }
                  } yield
                    (
                      isController,
                      Remote(
                        NonEmptyString.unsafeFrom(s"sonos-${remoteName.value.toLowerCase}"),
                        remoteName,
                        buttons,
                        Set(activityName),
                        if (allRooms) List.empty
                        else List(remoteName),
                        None,
                        nowPlaying.fold(Map.empty[String, String])(_.toMap)
                      )
                    )
              })
              .map(
                remotes =>
                  ConfigResult(
                    remotes
                      .sortBy(_._2.name.value)
                      .sortBy(!_._1)
                      .map { case (_, remote) => remote.name -> remote }
                      .toMap,
                    List.empty
                )
              )
          )

        override def getValue(key: NonEmptyString): F[Option[Remote]] = getConfig.map(_.values.get(key))

        override def functor: Functor[F] = F
      },
      "remotes",
      "sonos"
    )
  }
}
