package io.janstenpickle.controller.tplink

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.components.Components
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.remotecontrol.RemoteControlErrors
import io.janstenpickle.controller.tplink.hs100.{HS100Errors, HS100SmartPlug, HS100SwitchProvider}
import natchez.Trace

object TplinkComponents {
  case class Config(hs100: List[HS100SmartPlug.Config] = List.empty, pollingConfig: HS100SmartPlug.PollingConfig)

  def apply[F[_]: Sync: ContextShift: Parallel: RemoteControlErrors: HS100Errors: PollingSwitchErrors: Trace, G[_]: Concurrent: Timer](
    config: Config,
    onUpdate: State => F[Unit],
    blocker: Blocker
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, Components[F]] =
    HS100SwitchProvider(config.hs100, config.pollingConfig, onUpdate, blocker).map { switches =>
      Components.componentsMonoid[F].empty.copy(switches = switches)
    }
}
