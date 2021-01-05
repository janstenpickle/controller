package io.janstenpickle.controller.broadlink.switch

import cats.MonadError
import cats.effect.Clock
import cats.syntax.functor._
import io.janstenpickle.controller.broadlink.{BroadlinkDevice, BroadlinkDiscovery}
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.SwitchKey
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}
import io.janstenpickle.trace4cats.inject.Trace
import io.janstepickle.controller.events.switch.EventingSwitch

object SpSwitchProvider {
  def apply[F[_]: MonadError[*[_], Throwable]: Trace: Clock](
    discovery: BroadlinkDiscovery[F],
    switchEventPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  ): SwitchProvider[F] = new SwitchProvider[F] {
    override def getSwitches: F[Map[SwitchKey, Switch[F]]] =
      discovery.devices.map(_.devices.collect {
        case (_, BroadlinkDevice.Switch(switch)) =>
          SwitchKey(switch.device, switch.name) -> EventingSwitch(TracedSwitch(switch), switchEventPublisher)
      })
  }
}
