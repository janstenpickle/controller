package io.janstenpickle.controller.broadlink.switch

import cats.Parallel
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync, Timer}
import cats.instances.list._
import cats.syntax.traverse._
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.broadlink.switch.SpSwitch.PollingConfig
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.store.SwitchStateStore
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}
import natchez.Trace

object SpSwitchProvider {
  def apply[F[_]: Sync: Parallel: ContextShift: PollingSwitchErrors: Trace, G[_]: Concurrent: Timer](
    configs: List[SpSwitchConfig],
    pollingConfig: PollingConfig,
    store: SwitchStateStore[F],
    blocker: Blocker,
    onUpdate: State => F[Unit]
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, SwitchProvider[F]] = {
    type Res[A] = Resource[F, A]

    configs
      .traverse[Res, Switch[F]](SpSwitch.polling[F, G](_, pollingConfig, store, blocker, onUpdate))
      .map { switches =>
        SwitchProvider[F](switches.map(s => SwitchKey(s.device, s.name) -> s).toMap)
      }
  }
}
