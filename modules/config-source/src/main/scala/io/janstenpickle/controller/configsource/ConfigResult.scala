package io.janstenpickle.controller.configsource

import cats.Eq
import cats.kernel.Monoid
import cats.derived.semi
import cats.instances.map._
import cats.instances.list._
import cats.instances.string._
import eu.timepit.refined.cats._

case class ConfigResult[K, V](values: Map[K, V], errors: List[String] = List.empty)

object ConfigResult {
  implicit def configResultMonoid[K, V]: Monoid[ConfigResult[K, V]] = new Monoid[ConfigResult[K, V]] {
    override def empty: ConfigResult[K, V] = ConfigResult(Map.empty, List.empty)

    override def combine(x: ConfigResult[K, V], y: ConfigResult[K, V]): ConfigResult[K, V] =
      ConfigResult[K, V](x.values ++ y.values, x.errors ++ y.errors)
  }

  implicit def configResultEq[K: Eq, V: Eq]: Eq[ConfigResult[K, V]] = semi.eq
}
