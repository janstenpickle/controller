package io.janstenpickle.controller.switch

import cats.FlatMap
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.model.SwitchKey

trait Switches[F[_]] {
  def getState(device: NonEmptyString, name: NonEmptyString): F[State]
  def switchOn(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def switchOff(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def toggle(device: NonEmptyString, name: NonEmptyString): F[Unit]
  def list: F[List[SwitchKey]]
}

object Switches {
  def apply[F[_]: FlatMap: SwitchErrors](switches: SwitchProvider[F])(implicit errors: SwitchErrors[F]): Switches[F] =
    new Switches[F] {
      private def exec[A](device: NonEmptyString, switch: NonEmptyString)(f: Switch[F] => F[A]): F[A] =
        switches.getSwitches.flatMap(
          _.get(SwitchKey(device, switch)).fold[F[A]](errors.missingSwitch(device, switch))(f)
        )

      override def getState(device: NonEmptyString, name: NonEmptyString): F[State] = exec(device, name)(_.getState)
      override def switchOn(device: NonEmptyString, name: NonEmptyString): F[Unit] = exec(device, name)(_.switchOn)
      override def switchOff(device: NonEmptyString, name: NonEmptyString): F[Unit] = exec(device, name)(_.switchOff)
      override def toggle(device: NonEmptyString, name: NonEmptyString): F[Unit] = exec(device, name)(_.toggle)
      override def list: F[List[SwitchKey]] = switches.getSwitches.map(_.keys.toList)
    }
}
