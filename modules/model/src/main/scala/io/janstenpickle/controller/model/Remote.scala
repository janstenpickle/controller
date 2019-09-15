package io.janstenpickle.controller.model

import cats.Eq
import cats.data.NonEmptyList
import cats.derived.semi
import cats.instances.list._
import cats.instances.map._
import cats.instances.string._
import cats.kernel.Monoid
import eu.timepit.refined.types.string.NonEmptyString

case class Remote(
  name: NonEmptyString,
  buttons: NonEmptyList[Button],
  activities: List[NonEmptyString] = List.empty,
  rooms: List[Room] = List.empty,
  metadata: Map[String, String] = Map.empty
)

object Remote {
  implicit val eq: Eq[Remote] = semi.eq
}

case class Remotes(remotes: List[Remote], errors: List[String] = List.empty)

object Remotes {
  implicit val eq: Eq[Remotes] = semi.eq
  implicit val monoid: Monoid[Remotes] = semi.monoid
  implicit val setErrors: SetErrors[Remotes] = SetErrors((remotes, errors) => remotes.copy(errors = errors))
}
