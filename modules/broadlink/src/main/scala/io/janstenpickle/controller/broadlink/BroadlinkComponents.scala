package io.janstenpickle.controller.broadlink

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import cats.kernel.Monoid
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.broadlink.remote.{RmRemoteConfig, RmRemoteControls}
import io.janstenpickle.controller.broadlink.switch.{SpSwitchConfig, SpSwitchProvider}
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.configsource.WritableConfigSource
import io.janstenpickle.controller.discovery.DeviceRename
import io.janstenpickle.controller.model.{CommandPayload, DiscoveredDeviceKey, DiscoveredDeviceValue}
import io.janstenpickle.controller.remotecontrol.RemoteControlErrors
import io.janstenpickle.controller.store.{RemoteCommandStore, SwitchStateStore}
import natchez.Trace
import cats.syntax.semigroup._

object BroadlinkComponents {
  case class Config(
    enabled: Boolean = false,
    rm: List[RmRemoteConfig] = List.empty,
    sp: List[SpSwitchConfig] = List.empty,
    discovery: BroadlinkDiscovery.Config
  )

  def apply[F[_]: Sync: Parallel: ContextShift: Timer: PollingSwitchErrors: Trace: RemoteControlErrors, G[_]: Concurrent: Timer](
    config: Config,
    remoteStore: RemoteCommandStore[F, CommandPayload],
    switchStore: SwitchStateStore[F],
    nameMapping: WritableConfigSource[F, DiscoveredDeviceKey, DiscoveredDeviceValue],
    workBlocker: Blocker,
    discoveryBlocker: Blocker,
    onUpdate: () => F[Unit],
    onDeviceUpdate: () => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, Components[F]] =
    (if (config.enabled)
       for {
         static <- BroadlinkDiscovery
           .static[F, G](
             config.rm,
             config.sp,
             switchStore,
             workBlocker,
             config.discovery.polling,
             onDeviceUpdate: () => F[Unit]
           )
         (rename, dynamic) <- BroadlinkDiscovery
           .dynamic[F, G](
             config.discovery,
             workBlocker,
             discoveryBlocker,
             switchStore,
             nameMapping,
             onUpdate,
             onDeviceUpdate
           )
       } yield (rename, static |+| dynamic)
     else
       BroadlinkDiscovery
         .static[F, G](
           config.rm,
           config.sp,
           switchStore,
           workBlocker,
           config.discovery.polling,
           onDeviceUpdate: () => F[Unit]
         )
         .map(DeviceRename.empty[F] -> _)).map {
      case (rename, discovery) =>
        Monoid[Components[F]].empty
          .copy(
            remotes = RmRemoteControls(discovery, remoteStore),
            switches = SpSwitchProvider(discovery),
            rename = rename
          )
    }
}
