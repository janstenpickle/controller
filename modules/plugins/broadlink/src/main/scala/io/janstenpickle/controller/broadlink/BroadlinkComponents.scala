package io.janstenpickle.controller.broadlink

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.kernel.Monoid
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.broadlink.remote.{RmRemoteConfig, RmRemoteControls}
import io.janstenpickle.controller.broadlink.switch.{SpSwitchConfig, SpSwitchProvider}
import io.janstenpickle.controller.cache.CacheResource
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.event.{ConfigEvent, DeviceDiscoveryEvent, RemoteEvent, SwitchEvent}
import io.janstenpickle.controller.model.{CommandPayload, DiscoveredDeviceKey, DiscoveredDeviceValue}
import io.janstenpickle.controller.remote.store.RemoteCommandStore
import io.janstenpickle.controller.remotecontrol.{RemoteControlErrors, RemoteControls}
import io.janstenpickle.controller.switches.store.SwitchStateStore
import natchez.Trace

import scala.concurrent.duration._

object BroadlinkComponents {
  case class Config(
    enabled: Boolean = false,
    rm: List[RmRemoteConfig] = List.empty,
    sp: List[SpSwitchConfig] = List.empty,
    discovery: BroadlinkDiscovery.Config,
    remotesCacheTimeout: FiniteDuration = 10.seconds
  )

  def apply[F[_]: Concurrent: Parallel: ContextShift: Timer: PollingSwitchErrors: Trace: RemoteControlErrors, G[_]: Concurrent: Timer](
    config: Config,
    remoteStore: RemoteCommandStore[F, CommandPayload],
    switchStore: SwitchStateStore[F],
    nameMapping: WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    workBlocker: Blocker,
    discoveryBlocker: Blocker,
    remoteEventPublisher: EventPublisher[F, RemoteEvent],
    switchEventPublisher: EventPublisher[F, SwitchEvent],
    configEventPublisher: EventPublisher[F, ConfigEvent],
    discoveryEventPublisher: EventPublisher[F, DeviceDiscoveryEvent]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, Components[F]] =
    if (config.enabled)
      for {
        remotesCache <- CacheResource[F, RemoteControls[F]](config.remotesCacheTimeout, classOf)

        discovery <- BroadlinkDiscovery
          .dynamic[F, G](
            config.discovery,
            workBlocker,
            discoveryBlocker,
            switchStore,
            nameMapping,
            remoteEventPublisher,
            switchEventPublisher,
            configEventPublisher,
            discoveryEventPublisher
          )
      } yield {
        Monoid[Components[F]].empty
          .copy(
            remotes = RmRemoteControls(discovery, remoteStore, remotesCache, remoteEventPublisher),
            switches = SpSwitchProvider(discovery, switchEventPublisher.narrow),
            rename = BroadlinkDeviceRename(discovery, nameMapping, discoveryEventPublisher)
          )
      } else
      Resource.pure[F, Components[F]](Monoid[Components[F]].empty)

}
