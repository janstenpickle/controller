package io.janstenpickle.controller.multiswitch

import cats.effect.Clock
import cats.syntax.applicative._
import cats.syntax.functor._
import cats.syntax.parallel._
import cats.{MonadError, Parallel}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller
import io.janstenpickle.controller.configsource.ConfigSource
import io.janstenpickle.controller.events.EventPublisher
import io.janstenpickle.controller.model.{MultiSwitch, State, SwitchAction, SwitchKey, SwitchMetadata, SwitchType}
import io.janstenpickle.controller.model.event.SwitchEvent.SwitchStateUpdateEvent
import io.janstenpickle.controller.switch.trace.TracedSwitch
import io.janstenpickle.controller.switch._
import io.janstenpickle.trace4cats.inject.Trace
import io.janstepickle.controller.events.switch.EventingSwitch

object MultiSwitchProvider {
  def apply[F[_]: MonadError[*[_], Throwable]: Parallel: Trace: Clock](
    config: ConfigSource[F, NonEmptyString, MultiSwitch],
    switches: Switches[F],
    eventPublisher: EventPublisher[F, SwitchStateUpdateEvent]
  ): SwitchProvider[F] =
    new SwitchProvider[F] {
      override def getSwitches: F[Map[SwitchKey, Switch[F]]] = config.getConfig.map { multiSwitches =>
        multiSwitches.values.map {
          case (_, switch) =>
            val all = switch.primary :: switch.secondaries
            val key = SwitchKey(DeviceName, switch.name)

            key -> EventingSwitch(
              TracedSwitch(new Switch[F] {
                override def name: NonEmptyString = switch.name
                override def device: NonEmptyString = DeviceName

                override def getState: F[State] = {
                  val ref = switch.primary
                  switches.getState(ref.device, ref.name)
                }

                override def switchOn: F[Unit] =
                  all.parTraverse { ref =>
                    ref.onAction match {
                      case SwitchAction.Nothing => ().pure
                      case SwitchAction.Perform => switches.switchOn(ref.device, ref.name)
                      case SwitchAction.Opposite => switches.switchOff(ref.device, ref.name)
                    }
                  }.void

                override def switchOff: F[Unit] =
                  all.parTraverse { ref =>
                    ref.offAction match {
                      case SwitchAction.Nothing => ().pure
                      case SwitchAction.Perform => switches.switchOff(ref.device, ref.name)
                      case SwitchAction.Opposite => switches.switchOn(ref.device, ref.name)
                    }
                  }.void

                override def metadata: SwitchMetadata = makeMetadata(switch)
              }),
              eventPublisher
            )
        }
      }
    }
}
