package io.janstenpickle.controller.sonos

import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.{ActivityConfigSource, RemoteConfigSource}
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControlErrors}
import io.janstenpickle.controller.sonos.config.{SonosActivityConfigSource, SonosRemoteConfigSource}
import io.janstenpickle.controller.switch.SwitchProvider

import scala.concurrent.ExecutionContext

case class SonosComponents[F[_]] private (
  remote: RemoteControl[F],
  activityConfig: ActivityConfigSource[F],
  remoteConfig: RemoteConfigSource[F],
  switches: SwitchProvider[F]
)

object SonosComponents {
  case class Config(
    activity: SonosActivityConfigSource.Config,
    remoteName: Option[NonEmptyString],
    combinedDeviceName: Option[NonEmptyString],
    switchDeviceName: Option[NonEmptyString],
    polling: SonosDiscovery.Polling
  ) {
    lazy val remote: NonEmptyString = remoteName.getOrElse(activity.remoteName)
    lazy val combinedDevice: NonEmptyString = combinedDeviceName.getOrElse(remote)
    lazy val switchDevice: NonEmptyString = switchDeviceName.getOrElse(remote)
    lazy val activityConfig: SonosActivityConfigSource.Config =
      activity.copy(remoteName = remote, combinedDeviceName = combinedDevice)
  }

  def apply[F[_]: Concurrent: ContextShift: Timer](
    config: Config,
    onUpdate: Map[NonEmptyString, SonosDevice[F]] => F[Unit],
    ec: ExecutionContext
  )(implicit errors: RemoteControlErrors[F]): Resource[F, SonosComponents[F]] = {
    SonosDiscovery.polling[F](config.polling, onUpdate, ec).map { discovery =>
      val remote = SonosRemoteControl[F](config.remote, config.combinedDevice, discovery)
      val activityConfig = SonosActivityConfigSource[F](config.activity)
      val remoteConfig = SonosRemoteConfigSource[F](config.remote, config.activity.name, discovery)
      val switches = SonosSwitchProvider[F](config.switchDevice, discovery)

      SonosComponents(remote, activityConfig, remoteConfig, switches)
    }
  }

}
