package io.janstenpickle.controller.broadlink

import cats.Parallel
import cats.effect.{Async, Blocker, Concurrent, ContextShift, Resource, Timer}
import cats.kernel.Monoid
import cats.syntax.semigroup._
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.broadlink.remote.{RmRemoteConfig, RmRemoteControls}
import io.janstenpickle.controller.broadlink.switch.{SpSwitchConfig, SpSwitchProvider}
import io.janstenpickle.controller.cache.CacheResource
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.model.{CommandPayload, DiscoveredDeviceKey, DiscoveredDeviceValue}
import io.janstenpickle.controller.remotecontrol.{RemoteControlErrors, RemoteControls}
import io.janstenpickle.controller.store.{RemoteCommandStore, SwitchStateStore}
import natchez.Trace

import scala.concurrent.duration._

object BroadlinkComponents {
  case class Config(
    enabled: Boolean = false,
    rm: List[RmRemoteConfig] = List.empty,
    sp: List[SpSwitchConfig] = List.empty,
    dynamicDiscovery: Boolean = true,
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
    onUpdate: () => F[Unit],
    onDeviceUpdate: () => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, Components[F]] =
    if (config.enabled)
      for {
        remotesCache <- CacheResource[F, RemoteControls[F]](config.remotesCacheTimeout, classOf)
        static <- BroadlinkDiscovery
          .static[F, G](
            config.rm,
            config.sp,
            switchStore,
            workBlocker,
            config.discovery.polling,
            onDeviceUpdate: () => F[Unit]
          )
        discovery <- if (config.dynamicDiscovery)
          BroadlinkDiscovery
            .dynamic[F, G](
              config.discovery,
              workBlocker,
              discoveryBlocker,
              switchStore,
              nameMapping,
              onUpdate,
              onDeviceUpdate
            )
            .map(static |+| _)
        else
          Resource.pure[F, BroadlinkDiscovery[F]](static)
      } yield {
        Monoid[Components[F]].empty
          .copy(
            remotes = RmRemoteControls(discovery, remoteStore, remotesCache),
            switches = SpSwitchProvider(discovery),
            rename =
              if (config.dynamicDiscovery) BroadlinkDeviceRename(discovery, nameMapping) else DeviceRename.empty[F]
          )
      } else
      Resource.pure(Monoid[Components[F]].empty)

}
