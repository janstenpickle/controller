package io.janstenpickle.controller.switch.hs100

import cats.effect._
import cats.instances.list._
import cats.syntax.traverse._
import cats.~>
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.arrow.ContextualLiftLower
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}
import natchez.Trace

object HS100SwitchProvider {
  def apply[F[_]: Sync: ContextShift: HS100Errors: PollingSwitchErrors: Trace, G[_]: Concurrent: Timer](
    config: List[HS100SmartPlug.Config],
    pollingConfig: HS100SmartPlug.PollingConfig,
    onUpdate: State => F[Unit],
    blocker: Blocker
  )(implicit liftLower: ContextualLiftLower[G, F, String]): Resource[F, SwitchProvider[F]] = {
    type Res[A] = Resource[F, A]
    config
      .traverse[Res, (SwitchKey, Switch[F])](
        HS100SmartPlug
          .polling(_, pollingConfig, onUpdate, blocker)
          .map(s => SwitchKey(s.device, s.name) -> s)
      )
      .map { switches =>
        SwitchProvider(switches.toMap)
      }
  }
}
