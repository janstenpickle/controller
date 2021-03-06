package io.janstenpickle.controller.kodi

import java.net.InetAddress
import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.kernel.Monoid
import cats.syntax.monoid._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.cache.CacheResource
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource, WritableConfigSource}
import io.janstenpickle.controller.discovery.{DeviceRename, Discovery}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.kodi.KodiDiscovery.KodiInstance
import io.janstenpickle.controller.kodi.config.{KodiActivityConfigSource, KodiRemoteConfigSource}
import io.janstenpickle.controller.model.event.{ConfigEvent, DeviceDiscoveryEvent, RemoteEvent, SwitchEvent}
import io.janstenpickle.controller.model.{Button, Command, DiscoveredDeviceKey, DiscoveredDeviceValue, Remote}
import io.janstenpickle.controller.remotecontrol.RemoteControlErrors
import io.janstenpickle.controller.schedule.Scheduler
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}
import org.http4s.client.Client

import scala.concurrent.duration._

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
    discoveryBlocker: Blocker,
    config: Config,
    discoveryNameMapping: WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    remoteEventPublisher: EventPublisher[F, RemoteEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    configEventPublisher: EventPublisher[F, ConfigEvent],
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[F, Components[F]] =
    if (config.enabled)
      for {
        remotesCache <- CacheResource[F, ConfigResult[NonEmptyString, Remote]](config.remotesCacheTimeout, classOf)
        staticDiscovery <- KodiDiscovery
          .static[F, G](
            client,
            config,
            remoteEventPublisher,
            switchEventPublisher,
            configEventPublisher,
            discoveryEventPublisher,
            k
          )
        discovery <- if (config.dynamicDiscovery)
          KodiDiscovery
            .dynamic[F, G](
              client,
              discoveryBlocker,
              config,
              discoveryNameMapping,
              remoteEventPublisher,
              switchEventPublisher,
              configEventPublisher,
              discoveryEventPublisher,
              k
            )
            .map(_ |+| staticDiscovery)
        else Resource.pure[F, KodiDiscovery[F]](staticDiscovery)
        remote <- Resource.liftF(KodiRemoteControl(discovery, remoteEventPublisher))
      } yield
        Components[F](
          remote,
          KodiSwitchProvider(config.switchDevice, discovery, switchEventPublisher.narrow),
          rename =
            if (config.dynamicDiscovery) KodiDeviceRename[F](discovery, discoveryNameMapping, discoveryEventPublisher)
            else DeviceRename.empty[F],
          Monoid[Scheduler[F]].empty,
          KodiActivityConfigSource(config.activityConfig, discovery),
          KodiRemoteConfigSource(config.remote, config.activityConfig.name, discovery, remotesCache),
          ConfigSource.empty[F, NonEmptyString, NonEmptyList[Command]],
          ConfigSource.empty[F, String, Button]
        )
    else
      Resource.pure[F, Components[F]](Monoid[Components[F]].empty)
}
