package io.janstenpickle.controller.switch

import cats.FlatMap
import eu.timepit.refined.types.string.NonEmptyString

class Switches[F[_]: FlatMap](switches: Map[NonEmptyString, Switch[F]])(implicit errors: SwitchErrors[F]) {
  private def exec[A](switch: NonEmptyString)(f: Switch[F] => F[A]): F[A] =
    switches.get(switch).fold[F[A]](errors.missingSwitch(switch))(f)

  def getState(name: NonEmptyString): F[State] = exec(name)(_.getState)
  def switchOn(name: NonEmptyString): F[Unit] = exec(name)(_.switchOn)
  def switchOff(name: NonEmptyString): F[Unit] = exec(name)(_.switchOff)
  def toggle(name: NonEmptyString): F[Unit] = exec(name)(_.toggle)
  def list: List[NonEmptyString] = switches.keys.toList
}
