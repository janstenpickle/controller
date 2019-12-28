package io.janstenpickle.controller.schedule

import java.time.DayOfWeek

import cats.{Eq, Order}
import cats.data.NonEmptySet
import cats.instances.int._

package object model {
  type Days = NonEmptySet[DayOfWeek]

  implicit val dayOfWeekOrder: Order[DayOfWeek] = Order.by(_.getValue())
  implicit val dayOfWeekOrdering: Ordering[DayOfWeek] = dayOfWeekOrder.toOrdering
  implicit val dayOfWeekEq: Eq[DayOfWeek] = Eq.by(_.getValue)
}
