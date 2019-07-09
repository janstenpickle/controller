package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.boolean._
import cats.instances.list._
import cats.instances.option._
import cats.instances.string._
import cats.kernel.Monoid
import eu.timepit.refined.types.string.NonEmptyString

case class Activity(
  name: NonEmptyString,
  label: NonEmptyString,
  contextButtons: List[ContextButtonMapping] = List.empty,
  isActive: Option[Boolean],
  room: NonEmptyString = DefaultRoom
)

object Activity {
  implicit val eq: Eq[Activity] = semi.eq
}

case class Activities(activities: List[Activity], errors: List[String] = List.empty)

object Activities {
  implicit val eq: Eq[Activities] = semi.eq
  implicit val monoid: Monoid[Activities] = semi.monoid
  implicit val setErrors: SetErrors[Activities] = SetErrors((activities, errors) => activities.copy(errors = errors))
}
