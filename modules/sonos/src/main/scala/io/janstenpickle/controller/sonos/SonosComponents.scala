package io.janstenpickle.controller.sonos

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.cache.CacheResource
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.discovery.Discovery
import io.janstenpickle.controller.model.{Activity, Remote}
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControlErrors}
import io.janstenpickle.controller.sonos.config.{SonosActivityConfigSource, SonosRemoteConfigSource}
import io.janstenpickle.controller.switch.SwitchProvider
import natchez.Trace

import scala.concurrent.duration._

case class SonosComponents[F[_]] private (
  remote: RemoteControl[F],
  activityConfig: ConfigSource[F, String, Activity],
  remoteConfig: ConfigSource[F, NonEmptyString, Remote],
  switches: SwitchProvider[F]
)

object SonosComponents {
  case class Config(
    activity: SonosActivityConfigSource.Config,
    remoteName: Option[NonEmptyString],
    combinedDeviceName: Option[NonEmptyString],
    switchDeviceName: Option[NonEmptyString],
    polling: Discovery.Polling,
    commandTimeout: FiniteDuration = 5.seconds,
    remotesCacheTimeout: FiniteDuration = 2.seconds,
    allRooms: Boolean = true
  ) {
    lazy val remote: NonEmptyString = remoteName.getOrElse(activity.remoteName)
    lazy val combinedDevice: NonEmptyString = combinedDeviceName.getOrElse(remote)
    lazy val switchDevice: NonEmptyString = switchDeviceName.getOrElse(remote)
    lazy val activityConfig: SonosActivityConfigSource.Config =
      activity.copy(remoteName = remote, combinedDeviceName = combinedDevice)
  }

  def apply[F[_]: Concurrent: ContextShift: Timer: Parallel: Trace: RemoteControlErrors, G[_]: Concurrent: Timer](
    config: Config,
    onUpdate: () => F[Unit],
    blocker: Blocker,
    onDeviceUpdate: () => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, SonosComponents[F]] =
    for {
      remotesCache <- CacheResource[F, ConfigResult[NonEmptyString, Remote]](config.remotesCacheTimeout, classOf)
      discovery <- SonosDiscovery
        .polling[F, G](config.polling, config.commandTimeout, onUpdate, blocker, onDeviceUpdate)
    } yield {
      val remote = SonosRemoteControl[F](config.remote, config.combinedDevice, discovery)
      val activityConfig = SonosActivityConfigSource[F](config.activity, discovery)
      val remoteConfig =
        SonosRemoteConfigSource[F](config.remote, config.activity.name, config.allRooms, discovery, remotesCache)
      val switches = SonosSwitchProvider[F](config.switchDevice, discovery)

      SonosComponents(remote, activityConfig, remoteConfig, switches)
    }

}
