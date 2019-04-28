package io.janstenpickle.controller.multiswitch

import cats.Applicative
import cats.syntax.applicative._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.configsource.MultiSwitchConfigSource
import io.janstenpickle.controller.model.{State, SwitchAction}
import io.janstenpickle.controller.switch.model.SwitchKey
import io.janstenpickle.controller.switch.{Switch, SwitchProvider, Switches}

object MultiSwitchProvider {
  private val deviceName: NonEmptyString = NonEmptyString("multi")

  def apply[F[_]: Applicative](config: MultiSwitchConfigSource[F], switches: Switches[F]): SwitchProvider[F] =
    new SwitchProvider[F] {
      override def getSwitches: F[Map[SwitchKey, Switch[F]]] = config.getMultiSwitches.map { multiSwitches =>
        multiSwitches.multiSwitches.map { switch =>
          val all = switch.primary :: switch.secondaries

          SwitchKey(deviceName, switch.name) -> new Switch[F] {
            override def name: NonEmptyString = switch.name
            override def device: NonEmptyString = deviceName

            override def getState: F[State] = {
              val ref = switch.primary
              switches.getState(ref.device, ref.name)
            }

            override def switchOn: F[Unit] =
              all.traverse { ref =>
                ref.onAction match {
                  case SwitchAction.Nothing => ().pure
                  case SwitchAction.Perform => switches.switchOn(ref.device, ref.name)
                  case SwitchAction.Opposite => switches.switchOff(ref.device, ref.name)
                }
              }.void

            override def switchOff: F[Unit] =
              all.traverse { ref =>
                ref.offAction match {
                  case SwitchAction.Nothing => ().pure
                  case SwitchAction.Perform => switches.switchOff(ref.device, ref.name)
                  case SwitchAction.Opposite => switches.switchOn(ref.device, ref.name)
                }
              }.void
          }
        }.toMap
      }
    }
}
