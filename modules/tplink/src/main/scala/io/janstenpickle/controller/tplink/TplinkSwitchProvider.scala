package io.janstenpickle.controller.tplink

import cats.Applicative
import cats.syntax.functor._
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.switch.{Switch, SwitchProvider}
import natchez.Trace

object TplinkSwitchProvider {
  def apply[F[_]: Applicative: Trace](discovery: TplinkDiscovery[F]): SwitchProvider[F] = new SwitchProvider[F] {
    override def getSwitches: F[Map[SwitchKey, Switch[F]]] =
      discovery.devices.map(_.devices.map {
        case ((name, _), switch) =>
          SwitchKey(switch.device, name) -> TracedSwitch(switch)
      })
  }
}
