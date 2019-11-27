package io.janstenpickle.controller.switch

import cats.kernel.Monoid
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.{Applicative, Apply}
import io.janstenpickle.controller.switch.model.SwitchKey

trait SwitchProvider[F[_]] {
  def getSwitches: F[Map[SwitchKey, Switch[F]]]
}

object SwitchProvider { self =>
  def apply[F[_]: Applicative](switches: Map[SwitchKey, Switch[F]]): SwitchProvider[F] = new SwitchProvider[F] {
    override def getSwitches: F[Map[SwitchKey, Switch[F]]] = switches.pure[F]
  }

  def empty[F[_]: Applicative]: SwitchProvider[F] = apply[F](Map.empty)

  def combined[F[_]: Apply](x: SwitchProvider[F], y: SwitchProvider[F], rest: SwitchProvider[F]*): SwitchProvider[F] =
    (List(x, y) ++ rest).reduce { (l, r) =>
      combined(l, r)
    }

  def combined[F[_]: Apply](x: SwitchProvider[F], y: SwitchProvider[F]): SwitchProvider[F] = new SwitchProvider[F] {
    override def getSwitches: F[Map[SwitchKey, Switch[F]]] =
      x.getSwitches.map2(y.getSwitches)(_ ++ _)
  }

  implicit def switchProviderMonoid[F[_]: Applicative]: Monoid[SwitchProvider[F]] = new Monoid[SwitchProvider[F]] {
    override def empty: SwitchProvider[F] = self.empty[F]
    override def combine(x: SwitchProvider[F], y: SwitchProvider[F]): SwitchProvider[F] =
      self.combined[F](x, y)
  }
}
