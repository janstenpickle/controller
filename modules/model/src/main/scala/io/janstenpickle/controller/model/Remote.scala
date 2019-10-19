package io.janstenpickle.controller.model

import cats.Eq
import cats.data.NonEmptyList
import cats.derived.semi
import cats.instances.list._
import cats.instances.int._
import cats.instances.map._
import cats.instances.set._
import cats.instances.string._
import cats.instances.boolean._
import eu.timepit.refined.types.string.NonEmptyString

case class Remote(
  name: NonEmptyString,
  label: NonEmptyString,
  buttons: List[Button],
  activities: Set[NonEmptyString] = Set.empty,
  rooms: List[Room] = List.empty,
  order: Option[Int] = None,
  metadata: Map[String, String] = Map.empty,
  editable: Boolean = false
)

object Remote {
  implicit val eq: Eq[Remote] = semi.eq
  implicit val setEditable: SetEditable[Remote] = SetEditable((remote, editable) => remote.copy(editable = editable))
}
