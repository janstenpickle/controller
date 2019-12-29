package io.janstenpickle.controller.stats.switch

import cats.Apply
import cats.syntax.apply._
import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.State
import io.janstenpickle.controller.switch.{AppendableSwitches, Metadata, SwitchProvider, Switches}
import io.janstenpickle.controller.switch.model.SwitchKey

object SwitchesStats {

  def apply[F[_]: Apply](
    underlying: AppendableSwitches[F]
  )(implicit stats: SwitchStatsRecorder[F]): AppendableSwitches[F] =
    new AppendableSwitches[F] {

      override def switchOn(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        underlying.switchOn(device, name) *> stats.recordSwitchOn(device, name)

      override def switchOff(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        underlying.switchOff(device, name) *> stats.recordSwitchOff(device, name)

      override def toggle(device: NonEmptyString, name: NonEmptyString): F[Unit] =
        underlying.toggle(device, name) *> stats.recordToggle(device, name)

      override def getState(device: NonEmptyString, name: NonEmptyString): F[State] = underlying.getState(device, name)

      override def getMetadata(device: NonEmptyString, name: NonEmptyString): F[Option[Metadata]] =
        underlying.getMetadata(device, name)

      override def list: F[Set[SwitchKey]] = underlying.list

      override def addProvider(switchProvider: SwitchProvider[F]): AppendableSwitches[F] =
        underlying.addProvider(switchProvider)
    }
}
