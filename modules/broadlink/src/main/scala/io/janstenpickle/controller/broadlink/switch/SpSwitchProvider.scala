package io.janstenpickle.controller.broadlink.switch

import cats.effect.{Concurrent, ContextShift, Resource, Timer}
import cats.instances.list._
import cats.syntax.traverse._
import io.janstenpickle.control.switch.polling.PollingSwitchErrors
import io.janstenpickle.controller.broadlink.switch.SpSwitch.PollingConfig
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.store.SwitchStateStore
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}

import scala.concurrent.ExecutionContext

object SpSwitchProvider {
  def apply[F[_]: Concurrent: ContextShift: Timer: PollingSwitchErrors](
    configs: List[SpSwitchConfig],
    pollingConfig: PollingConfig,
    store: SwitchStateStore[F],
    ec: ExecutionContext,
    onUpdate: State => F[Unit]
  ): Resource[F, SwitchProvider[F]] = {
    type Res[A] = Resource[F, A]

    configs.traverse[Res, Switch[F]](SpSwitch.polling[F](_, pollingConfig, store, ec, onUpdate)).map { switches =>
      SwitchProvider[F](switches.map(s => SwitchKey(s.device, s.name) -> s).toMap)
    }
  }
}
