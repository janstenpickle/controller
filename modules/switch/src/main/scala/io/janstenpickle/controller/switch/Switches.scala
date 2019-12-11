package io.janstenpickle.controller.switch

import cats.Monad
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.model.SwitchKey
import natchez.Trace

trait Switches[F[_]] {
  def getState(device: NonEmptyString, name: NonEmptyString): F[State]
  def switchOn(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def switchOff(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def toggle(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def list: F[Set[SwitchKey]]
}

object Switches {
  def apply[F[_]: Monad: SwitchErrors](
    switches: SwitchProvider[F]
  )(implicit errors: SwitchErrors[F], trace: Trace[F]): Switches[F] =
    new Switches[F] {
      private def span[A](name: String, device: NonEmptyString, switch: NonEmptyString)(fa: F[A]): F[A] =
        trace.span(name)(trace.put("device" -> device.value, "switch" -> switch.value) *> fa)

      private def exec[A](device: NonEmptyString, switch: NonEmptyString)(f: Switch[F] => F[A]): F[A] =
        switches.getSwitches.flatMap(
          _.get(SwitchKey(device, switch)).fold[F[A]](
            trace.put("switch.present" -> false, "error" -> true) *> errors.missingSwitch(device, switch)
          )(s => trace.put("switch.present" -> true, "error" -> false) *> f(s))
        )

      override def getState(device: NonEmptyString, name: NonEmptyString): F[State] =
        span("switches.get.state", device, name) {
          switches.getSwitches.flatMap(
            _.get(SwitchKey(device, name))
              .fold[F[State]](trace.put("switch.present" -> false, "switch.on" -> false).as(State.Off))(
                _.getState.flatTap(st => trace.put("switch.present" -> true, "switch.on" -> st.isOn))
              )
          )
        }

      override def switchOn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        span("switches.switch.on", device, name) {
          exec(device, name)(_.switchOn)
        }

      override def switchOff(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        span("switches.switch.off", device, name) {
          exec(device, name)(_.switchOff)
        }

      override def toggle(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        span("switchesToggle", device, name) {
          exec(device, name)(_.toggle)
        }

      override def list: F[Set[SwitchKey]] =
        trace.span("switchesList") {
          switches.getSwitches.map(_.keySet).flatTap(sw => trace.put("switches.count" -> sw.size))
        }
    }
}
