package io.janstenpickle.controller.stats.switch

import cats.Apply
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.Switches
import io.janstenpickle.controller.switch.model.SwitchKey

object SwitchesStats {

  def apply[F[_]: Apply](underlying: Switches[F])(implicit stats: SwitchStatsRecorder[F]): Switches[F] =
    new Switches[F] {

      override def switchOn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        underlying.switchOn(device, name) *> stats.recordSwitchOn(device, name)

      override def switchOff(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        underlying.switchOff(device, name) *> stats.recordSwitchOff(device, name)

      override def toggle(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        underlying.toggle(device, name) *> stats.recordToggle(device, name)

      override def getState(device: NonEmptyString, name: NonEmptyString): F[State] = underlying.getState(device, name)

      override def list: F[Set[SwitchKey]] = underlying.list
    }
}
