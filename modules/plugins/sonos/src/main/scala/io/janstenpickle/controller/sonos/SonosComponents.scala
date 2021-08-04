package io.janstenpickle.controller.sonos

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.kernel.Monoid
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.cache.CacheResource
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.configsource.{ConfigResult, ConfigSource}
import io.janstenpickle.controller.discovery.{DeviceRename, Discovery}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.{ConfigEvent, DeviceDiscoveryEvent, RemoteEvent, SwitchEvent}
import io.janstenpickle.controller.model.{Button, Command, Remote}
import io.janstenpickle.controller.remotecontrol.RemoteControlErrors
import io.janstenpickle.controller.schedule.Scheduler
import io.janstenpickle.controller.sonos.config.{SonosActivityConfigSource, SonosRemoteConfigSource}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.base.context.Provide
import io.janstenpickle.trace4cats.inject.{ResourceKleisli, SpanName, Trace}

import scala.concurrent.duration._

object SonosComponents {
  case class Config(
    activity: SonosActivityConfigSource.Config,
    remoteName: Option[NonEmptyString],
    combinedDeviceName: Option[NonEmptyString],
    switchDeviceName: Option[NonEmptyString],
    polling: Discovery.Polling,
    commandTimeout: FiniteDuration = 5.seconds,
    remotesCacheTimeout: FiniteDuration = 2.seconds,
    allRooms: Boolean = true,
    enabled: Boolean = false
  ) {
    lazy val remote: NonEmptyString = remoteName.getOrElse(activity.remoteName)
    lazy val combinedDevice: NonEmptyString = combinedDeviceName.getOrElse(remote)
    lazy val switchDevice: NonEmptyString = switchDeviceName.getOrElse(remote)
    lazy val activityConfig: SonosActivityConfigSource.Config =
      activity.copy(remoteName = remote, combinedDeviceName = combinedDevice)
  }

  def apply[F[_]: Async: Parallel: Trace: RemoteControlErrors, G[_]: Async](
    config: Config,
    remoteEventPublisher: EventPublisher[F, RemoteEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    configEventPublisher: EventPublisher[F, ConfigEvent],
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent],
    k: ResourceKleisli[G, SpanName, Span[G]]
  )(implicit provide: Provide[G, F, Span[G]]): Resource[F, Components[F]] =
    if (config.enabled)
      for {
        remotesCache <- CacheResource[F, ConfigResult[NonEmptyString, Remote]](config.remotesCacheTimeout, classOf)
        discovery <- SonosDiscovery
          .polling[F, G](
            config,
            remoteEventPublisher,
            switchEventPublisher,
            configEventPublisher,
            discoveryEventPublisher,
            k
          )
        remote <- Resource.eval(
          SonosRemoteControl[F](config.remote, config.combinedDevice, discovery, remoteEventPublisher)
        )
      } yield {
        val activityConfig = SonosActivityConfigSource[F](config.activity, discovery)
        val remoteConfig =
          SonosRemoteConfigSource[F](config.remote, config.activity.name, config.allRooms, discovery, remotesCache)
        val switches = SonosSwitchProvider[F](
          config.switchDevice,
          discovery,
          switchEventPublisher.narrow[SwitchEvent.SwitchStateUpdateEvent]
        )

        Components[F](
          remote,
          switches,
          DeviceRename.empty[F],
          Monoid[Scheduler[F]].empty,
          activityConfig,
          remoteConfig,
          ConfigSource.empty[F, NonEmptyString, NonEmptyList[Command]],
          ConfigSource.empty[F, String, Button]
        )
      } else
      Resource.pure[F, Components[F]](Monoid[Components[F]].empty)

}
