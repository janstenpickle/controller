package io.janstenpickle.controller.sonos.config

import cats.data.NonEmptyList
import cats.effect.Async
import cats.instances.list._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.RemoteConfigSource
import io.janstenpickle.controller.model.Button.{RemoteIcon, SwitchIcon}
import io.janstenpickle.controller.model.{Button, Remote, Remotes}
import io.janstenpickle.controller.sonos.{Commands, SonosDiscovery}
import scalacache.Cache
import scalacache.CatsEffect.modes._

object SonosRemoteConfigSource {
  def apply[F[_]](
    remoteName: NonEmptyString,
    activityName: NonEmptyString,
    allRooms: Boolean,
    discovery: SonosDiscovery[F],
    cache: Cache[Remotes]
  )(implicit F: Async[F]): RemoteConfigSource[F] = {
    def simpleTemplate(device: NonEmptyString): NonEmptyList[Button] =
      NonEmptyList.of(
        RemoteIcon(remoteName, device, Commands.Mute, NonEmptyString("volume_off"), Some(true), None, None),
        RemoteIcon(remoteName, device, Commands.VolDown, NonEmptyString("volume_down"), None, None, None),
        RemoteIcon(remoteName, device, Commands.VolUp, NonEmptyString("volume_up"), None, None, None)
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
            None
          )
        )

    def template(device: NonEmptyString, isPlaying: Boolean): NonEmptyList[Button] =
      NonEmptyList.of(
        RemoteIcon(remoteName, device, Commands.Previous, NonEmptyString("fast_rewind"), None, None, None),
        RemoteIcon(
          remoteName,
          device,
          Commands.PlayPause,
          if (isPlaying) NonEmptyString("pause") else NonEmptyString("play_arrow"),
          None,
          Some(isPlaying),
          None
        ),
        RemoteIcon(remoteName, device, Commands.Next, NonEmptyString("fast_forward"), None, None, None)
      ) ++ simpleTemplate(device).toList

    new RemoteConfigSource[F] {
      override def getRemotes: F[Remotes] =
        cache.cachingForMemoizeF(s"${remoteName.value}_remotes")(None)(
          discovery.devices
            .flatMap(_.values.toList.traverse { device =>
              for {
                isController <- device.isController
                isGrouped <- device.isGrouped
                buttons <- if (isController)
                  device.isPlaying.map(template(device.name, _) ++ groupTemplate(device.name, isController, isGrouped))
                else F.pure(simpleTemplate(device.name) ++ groupTemplate(device.name, isController, isGrouped))
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
                    remoteName,
                    buttons,
                    List(activityName),
                    if (allRooms) List.empty
                    else List(remoteName),
                    nowPlaying.fold(Map.empty[String, String])(_.toMap)
                  )
                )
            })
            .map(remotes => Remotes(remotes.sortBy(_._2.name.value).sortBy(!_._1).map(_._2), List.empty))
        )

    }
  }
}
