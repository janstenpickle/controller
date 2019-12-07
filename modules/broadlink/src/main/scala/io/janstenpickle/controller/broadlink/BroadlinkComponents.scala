package io.janstenpickle.controller.broadlink

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.broadlink.remote.{RmRemoteConfig, RmRemoteControls}
import io.janstenpickle.controller.broadlink.switch.{SpSwitchConfig, SpSwitchProvider}
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.model.{CommandPayload, DiscoveredDeviceKey, DiscoveredDeviceValue}
import io.janstenpickle.controller.remotecontrol.RemoteControlErrors
import io.janstenpickle.controller.store.{RemoteCommandStore, SwitchStateStore}
import natchez.Trace

object BroadlinkComponents {
  case class Config(
    rm: List[RmRemoteConfig] = List.empty,
    sp: List[SpSwitchConfig] = List.empty,
    discovery: BroadlinkDiscovery.Config
  )

  def apply[F[_]: Sync: Parallel: ContextShift: Timer: PollingSwitchErrors: Trace: RemoteControlErrors, G[_]: Concurrent: Timer](
    config: Config,
    remoteStore: RemoteCommandStore[F, CommandPayload],
    switchStore: SwitchStateStore[F],
    nameMapping: WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    blocker: Blocker,
    onUpdate: () => F[Unit],
    onDeviceUpdate: () => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, Components[F]] =
    BroadlinkDiscovery
      .dynamic[F, G](config.discovery, blocker, switchStore, nameMapping, onUpdate, onDeviceUpdate)
      .map {
        case (rename, discovery) =>
          Components
            .componentsMonoid[F]
            .empty
            .copy(
              remotes = RmRemoteControls(discovery, remoteStore),
              switches = SpSwitchProvider(discovery),
              rename = rename
            )
      }
}
