package io.janstenpickle.controller.tplink

import cats.MonadError
import cats.effect.Clock
import cats.syntax.functor._
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.SwitchKey
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}
import io.janstenpickle.trace4cats.inject.Trace
import io.janstepickle.controller.events.switch.EventingSwitch

object TplinkSwitchProvider {
  def apply[F[_]: MonadError[*[_], Throwable]: Trace: Clock](
    discovery: TplinkDiscovery[F],
    switchEventPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  ): SwitchProvider[F] = new SwitchProvider[F] {
    override def getSwitches: F[Map[SwitchKey, Switch[F]]] =
      discovery.devices.map(_.devices.map {
        case ((name, _), switch) =>
          SwitchKey(switch.device, name) -> EventingSwitch(TracedSwitch(switch), switchEventPublisher)
      })
  }
}
