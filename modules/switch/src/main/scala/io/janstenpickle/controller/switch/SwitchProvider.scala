package io.janstenpickle.controller.switch

import cats.syntax.applicative._
import cats.syntax.apply._
import cats.{Applicative, Apply}

trait SwitchProvider[F[_]] {
  def getSwitches: F[Map[SwitchKey, Switch[F]]]
}

object SwitchProvider {
  def apply[F[_]: Applicative](switches: Map[SwitchKey, Switch[F]]): SwitchProvider[F] = new SwitchProvider[F] {
    override def getSwitches: F[Map[SwitchKey, Switch[F]]] = switches.pure[F]
  }

  def combined[F[_]: Apply](x: SwitchProvider[F], y: SwitchProvider[F]): SwitchProvider[F] = new SwitchProvider[F] {
    override def getSwitches: F[Map[SwitchKey, Switch[F]]] =
      x.getSwitches.map2(y.getSwitches)(_ ++ _)
  }
}
