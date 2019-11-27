package io.janstenpickle.controller.broadlink

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.broadlink.remote.{RmRemoteConfig, RmRemoteControls}
import io.janstenpickle.controller.broadlink.switch.SpSwitch.PollingConfig
import io.janstenpickle.controller.broadlink.switch.{SpSwitchConfig, SpSwitchProvider}
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.model.{CommandPayload, State}
import io.janstenpickle.controller.remotecontrol.RemoteControlErrors
import io.janstenpickle.controller.store.{RemoteCommandStore, SwitchStateStore}
import natchez.Trace

object BroadlinkComponents {
  case class Config(
    rm: List[RmRemoteConfig] = List.empty,
    sp: List[SpSwitchConfig] = List.empty,
    pollingConfig: PollingConfig
  )

  def apply[F[_]: Sync: Parallel: ContextShift: Timer: PollingSwitchErrors: Trace: RemoteControlErrors, G[_]: Concurrent: Timer](
    config: Config,
    remoteStore: RemoteCommandStore[F, CommandPayload],
    switchStore: SwitchStateStore[F],
    blocker: Blocker,
    onSwitchUpdate: State => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, Components[F]] =
    for {
      remotes <- Resource.liftF(RmRemoteControls[F](config.rm, remoteStore, blocker))
      switches <- SpSwitchProvider[F, G](config.sp, config.pollingConfig, switchStore, blocker, onSwitchUpdate)
    } yield Components.componentsMonoid[F].empty.copy(remotes = remotes, switches = switches)

}
