package io.janstenpickle.controller.switch.hs100

import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}

import scala.concurrent.ExecutionContext
import cats.syntax.traverse._
import cats.instances.list._
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.model.SwitchKey

object HS100SwitchProvider {
  def apply[F[_]: Concurrent: ContextShift: Timer: HS100Errors: PollingSwitchErrors](
    config: List[HS100SmartPlug.Config],
    pollingConfig: HS100SmartPlug.PollingConfig,
    onUpdate: State => F[Unit],
    ec: ExecutionContext
  ): Resource[F, SwitchProvider[F]] = {
    type Res[A] = Resource[F, A]
    config
      .traverse[Res, (SwitchKey, Switch[F])](
        HS100SmartPlug.polling(_, pollingConfig, onUpdate, ec).map(s => SwitchKey(s.device, s.name) -> s)
      )
      .map { switches =>
        SwitchProvider(switches.toMap)
      }
  }
}
