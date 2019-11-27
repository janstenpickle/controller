package io.janstenpickle.controller.kodi

import java.net.InetAddress

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.kernel.Monoid
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.cache.CacheResource
import io.janstenpickle.controller.components
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.discovery.Discovery
import io.janstenpickle.controller.kodi.KodiDiscovery.KodiInstance
import io.janstenpickle.controller.kodi.config.{KodiActivityConfigSource, KodiRemoteConfigSource}
import io.janstenpickle.controller.model.{Activity, Command, Remote}
import io.janstenpickle.controller.remotecontrol.{RemoteControl, RemoteControlErrors}
import io.janstenpickle.controller.switch.SwitchProvider
import natchez.Trace
import org.http4s.client.Client

import scala.concurrent.duration._

case class KodiComponents[F[_]] private (
  remote: RemoteControl[F],
  switches: SwitchProvider[F],
  remoteConfig: ConfigSource[F, NonEmptyString, Remote],
  activityConfig: ConfigSource[F, String, Activity]
)

object KodiComponents {
  case class Config(
    polling: Discovery.Polling,
    remoteName: Option[NonEmptyString],
    activity: KodiActivityConfigSource.Config,
    switchDeviceName: Option[NonEmptyString],
    discoveryBindAddress: Option[InetAddress],
    instances: List[KodiInstance] = List.empty,
    dynamicDiscovery: Boolean = true,
    remotesCacheTimeout: FiniteDuration = 2.seconds,
    enabled: Boolean = false
  ) {
    lazy val remote: NonEmptyString = remoteName.getOrElse(activity.remoteName)
    lazy val switchDevice: NonEmptyString = switchDeviceName.getOrElse(remote)
    lazy val activityConfig: KodiActivityConfigSource.Config =
      activity.copy(remoteName = remote)
  }

  def apply[F[_]: Concurrent: ContextShift: Timer: Parallel: Trace: RemoteControlErrors: KodiErrors, G[_]: Concurrent: Timer](
    client: Client[F],
    blocker: Blocker,
    config: Config,
    onUpdate: () => F[Unit],
    onDeviceUpdate: () => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, Components[F]] =
    if (config.enabled)
      for {
        remotesCache <- CacheResource[F, ConfigResult[NonEmptyString, Remote]](config.remotesCacheTimeout, classOf)
        staticDiscovery <- KodiDiscovery.static[F, G](client, config.instances, config.polling, onDeviceUpdate)
        discovery <- if (config.dynamicDiscovery)
          KodiDiscovery
            .dynamic[F, G](client, blocker, config.discoveryBindAddress, config.polling, onUpdate, onDeviceUpdate)
            .map { dynamic =>
              Discovery.combined(dynamic, staticDiscovery)
            } else Resource.pure[F, KodiDiscovery[F]](staticDiscovery)

      } yield
        Components[F](
          KodiRemoteControl(discovery),
          KodiSwitchProvider(config.switchDevice, discovery),
          KodiActivityConfigSource(config.activityConfig, discovery),
          KodiRemoteConfigSource(config.remote, config.activityConfig.name, discovery, remotesCache),
          ConfigSource.empty[F, NonEmptyString, NonEmptyList[Command]]
        )
    else
      Resource.pure[F, Components[F]](Monoid[Components[F]].empty)
}
