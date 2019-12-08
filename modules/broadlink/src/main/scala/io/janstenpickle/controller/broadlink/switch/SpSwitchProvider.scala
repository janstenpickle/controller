package io.janstenpickle.controller.broadlink.switch

import cats.Applicative
import cats.syntax.functor._
import io.janstenpickle.controller.broadlink.BroadlinkDiscovery
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}
import natchez.Trace

object SpSwitchProvider {
  def apply[F[_]: Applicative: Trace](discovery: BroadlinkDiscovery[F]): SwitchProvider[F] = new SwitchProvider[F] {
    override def getSwitches: F[Map[SwitchKey, Switch[F]]] =
      discovery.devices.map(_.devices.collect {
        case (_, Left(switch)) =>
          SwitchKey(switch.device, switch.name) -> TracedSwitch(switch, SpSwitch.manufacturerField)
      })
  }
}
