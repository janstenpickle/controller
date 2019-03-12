package io.janstenpickle.controller.switch

import cats.FlatMap
import cats.syntax.flatMap._
import cats.syntax.functor._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.model.SwitchKey

class Switches[F[_]: FlatMap](switches: SwitchProvider[F])(implicit errors: SwitchErrors[F]) {
  private def exec[A](device: NonEmptyString, switch: NonEmptyString)(f: Switch[F] => F[A]): F[A] =
    switches.getSwitches.flatMap(_.get(SwitchKey(device, switch)).fold[F[A]](errors.missingSwitch(device, switch))(f))

  def getState(device: NonEmptyString, name: NonEmptyString): F[State] = exec(device, name)(_.getState)
  def switchOn(device: NonEmptyString, name: NonEmptyString): F[Unit] = exec(device, name)(_.switchOn)
  def switchOff(device: NonEmptyString, name: NonEmptyString): F[Unit] = exec(device, name)(_.switchOff)
  def toggle(device: NonEmptyString, name: NonEmptyString): F[Unit] = exec(device, name)(_.toggle)
  def list: F[List[SwitchKey]] = switches.getSwitches.map(_.keys.toList)
}
