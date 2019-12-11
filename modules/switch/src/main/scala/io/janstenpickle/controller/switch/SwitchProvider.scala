package io.janstenpickle.controller.switch

import cats.{Applicative, Parallel}
import cats.kernel.Monoid
import cats.syntax.applicative._
import cats.syntax.apply._
import io.janstenpickle.controller.switch.model.SwitchKey

trait SwitchProvider[F[_]] {
  def getSwitches: F[Map[SwitchKey, Switch[F]]]
}

object SwitchProvider extends LowPriorityInstances {
  implicit def switchProviderMonoidFromParallel[F[_]: Applicative: Parallel]: Monoid[SwitchProvider[F]] =
    new Monoid[SwitchProvider[F]] {
      override def empty: SwitchProvider[F] = apply[F](Map.empty)
      override def combine(x: SwitchProvider[F], y: SwitchProvider[F]): SwitchProvider[F] = new SwitchProvider[F] {
        override def getSwitches: F[Map[SwitchKey, Switch[F]]] =
          Parallel.parMap2(x.getSwitches, y.getSwitches)(_ ++ _)
      }
    }
}

trait LowPriorityInstances {
  def apply[F[_]: Applicative](switches: Map[SwitchKey, Switch[F]]): SwitchProvider[F] = new SwitchProvider[F] {
    override def getSwitches: F[Map[SwitchKey, Switch[F]]] = switches.pure[F]
  }

  implicit def switchProviderMonoid[F[_]: Applicative]: Monoid[SwitchProvider[F]] = new Monoid[SwitchProvider[F]] {
    override def empty: SwitchProvider[F] = apply[F](Map.empty)
    override def combine(x: SwitchProvider[F], y: SwitchProvider[F]): SwitchProvider[F] = new SwitchProvider[F] {
      override def getSwitches: F[Map[SwitchKey, Switch[F]]] =
        x.getSwitches.map2(y.getSwitches)(_ ++ _)
    }
  }
}
