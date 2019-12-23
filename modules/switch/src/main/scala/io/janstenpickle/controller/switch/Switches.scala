package io.janstenpickle.controller.switch

import cats.{~>, Applicative, Monad}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.model.SwitchKey
import natchez.Trace

trait Switches[F[_]] { outer =>
  def getState(device: NonEmptyString, name: NonEmptyString): F[State]
  def switchOn(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def switchOff(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def toggle(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def list: F[Set[SwitchKey]]
  def mapK[G[_]](fk: F ~> G): Switches[G] = new Switches[G] {
    override def getState(device: NonEmptyString, name: NonEmptyString): G[State] = fk(outer.getState(device, name))
    override def switchOn(device: NonEmptyString, name: NonEmptyString): G[Unit] = fk(outer.switchOn(device, name))
    override def switchOff(device: NonEmptyString, name: NonEmptyString): G[Unit] = fk(outer.switchOff(device, name))
    override def toggle(device: NonEmptyString, name: NonEmptyString): G[Unit] = fk(outer.toggle(device, name))
    override def list: G[Set[SwitchKey]] = fk(outer.list)
  }
}

object Switches {
  def apply[F[_]: Monad: SwitchErrors: Trace](switches: SwitchProvider[F]): Switches[F] =
    apply(switches, (_: SwitchKey) => Applicative[F].unit)

  def apply[F[_]: Monad](
    switches: SwitchProvider[F],
    onSwitchUpdate: SwitchKey => F[Unit]
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
          exec(device, name)(_.switchOn) *> onSwitchUpdate(SwitchKey(device, name))
        }

      override def switchOff(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        span("switches.switch.off", device, name) {
          exec(device, name)(_.switchOff) *> onSwitchUpdate(SwitchKey(device, name))
        }

      override def toggle(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        span("switches.toggle", device, name) {
          exec(device, name)(_.toggle) *> onSwitchUpdate(SwitchKey(device, name))
        }

      override def list: F[Set[SwitchKey]] =
        trace.span("switches.list") {
          switches.getSwitches.map(_.keySet).flatTap(sw => trace.put("count" -> sw.size))
        }
    }
}
