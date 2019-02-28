package io.janstenpickle.controller.model

import eu.timepit.refined.types.string.NonEmptyString

case class Activity(
  name: NonEmptyString,
  label: NonEmptyString,
  contextButtons: List[ContextButtonMapping],
  isActive: Option[Boolean]
)

case class Activities(activities: List[Activity], errors: List[String] = List.empty)
