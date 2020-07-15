package io.janstenpickle.controller.switch

import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._
import cats.{~>, Monad}
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{State, SwitchKey, SwitchMetadata}
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.SpanStatus

trait Switches[F[_]] { outer =>
  def getState(device: NonEmptyString, name: NonEmptyString): F[State]
  def getMetadata(device: NonEmptyString, name: NonEmptyString): F[Option[SwitchMetadata]]
  def switchOn(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def switchOff(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def toggle(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def list: F[Set[SwitchKey]]
  def mapK[G[_]](fk: F ~> G): Switches[G] = new Switches[G] {
    override def getState(device: NonEmptyString, name: NonEmptyString): G[State] = fk(outer.getState(device, name))
    override def getMetadata(device: NonEmptyString, name: NonEmptyString): G[Option[SwitchMetadata]] =
      fk(outer.getMetadata(device, name))
    override def switchOn(device: NonEmptyString, name: NonEmptyString): G[Unit] = fk(outer.switchOn(device, name))
    override def switchOff(device: NonEmptyString, name: NonEmptyString): G[Unit] = fk(outer.switchOff(device, name))
    override def toggle(device: NonEmptyString, name: NonEmptyString): G[Unit] = fk(outer.toggle(device, name))
    override def list: G[Set[SwitchKey]] = fk(outer.list)
  }
}

trait AppendableSwitches[F[_]] extends Switches[F] {
  def addProvider(switchProvider: SwitchProvider[F]): AppendableSwitches[F]
}

object Switches {
  def apply[F[_]: Monad](
    switches: SwitchProvider[F]
  )(implicit errors: SwitchErrors[F], trace: Trace[F]): AppendableSwitches[F] =
    new AppendableSwitches[F] {
      private def span[A](name: String, device: NonEmptyString, switch: NonEmptyString)(fa: F[A]): F[A] =
        trace.span(name)(trace.putAll("device" -> device.value, "switch" -> switch.value) *> fa)

      private def exec[A](device: NonEmptyString, switch: NonEmptyString)(f: Switch[F] => F[A]): F[A] =
        switches.getSwitches.flatMap(
          _.get(SwitchKey(device, switch)).fold[F[A]](
            trace.put("switch.present", false) *> trace.setStatus(SpanStatus.NotFound) *> errors
              .missingSwitch(device, switch)
          )(s => trace.put("switch.present", true) *> f(s))
        )

      override def getState(device: NonEmptyString, name: NonEmptyString): F[State] =
        span("switches.get.state", device, name) {
          switches.getSwitches.flatMap(
            _.get(SwitchKey(device, name))
              .fold[F[State]](trace.put("switch.on", false) *> trace.setStatus(SpanStatus.NotFound).as(State.Off))(
                _.getState.flatTap(st => trace.putAll("switch.present" -> true, "switch.on" -> st.isOn))
              )
          )
        }

      override def getMetadata(device: NonEmptyString, name: NonEmptyString): F[Option[SwitchMetadata]] =
        switches.getSwitches.map(_.get(SwitchKey(device, name)).map(_.metadata))

      override def switchOn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        span("switches.switch.on", device, name) {
          exec(device, name)(_.switchOn)
        }

      override def switchOff(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        span("switches.switch.off", device, name) {
          exec(device, name)(_.switchOff)
        }

      override def toggle(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        span("switches.toggle", device, name) {
          exec(device, name)(_.toggle)
        }

      override def list: F[Set[SwitchKey]] =
        trace.span("switches.list") {
          switches.getSwitches.map(_.keySet).flatTap(sw => trace.put("count", sw.size))
        }

      override def addProvider(switchProvider: SwitchProvider[F]): AppendableSwitches[F] =
        apply(switches |+| switchProvider)
    }
}
