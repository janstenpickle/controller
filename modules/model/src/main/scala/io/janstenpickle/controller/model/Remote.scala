package io.janstenpickle.controller.model

import cats.Eq
import cats.data.NonEmptyList
import cats.derived.semi
import cats.instances.list._
import cats.instances.string._
import eu.timepit.refined.types.string.NonEmptyString

case class Remote(name: NonEmptyString, activities: NonEmptyList[NonEmptyString], buttons: NonEmptyList[Button])

object Remote {
  implicit val eq: Eq[Remote] = semi.eq
}

case class Remotes(remotes: List[Remote], errors: List[String] = List.empty)

object Remotes {
  implicit val eq: Eq[Remotes] = semi.eq
}
