package io.janstenpickle.controller.model

import cats.Eq
import cats.derived.semi
import cats.instances.boolean._
import cats.instances.int._
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
  action: Option[Command] = None,
  room: NonEmptyString = DefaultRoom,
  order: Option[Int] = None,
  editable: Boolean = false
)

object Activity {
  implicit val eq: Eq[Activity] = semi.eq
  implicit val setEditable: SetEditable[Activity] = SetEditable(
    (activity, editable) => activity.copy(editable = editable)
  )
}
