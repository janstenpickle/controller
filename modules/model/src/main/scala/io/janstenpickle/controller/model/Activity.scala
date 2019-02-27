package io.janstenpickle.controller.model

import eu.timepit.refined.types.string.NonEmptyString

case class Activity(name: NonEmptyString, label: NonEmptyString, contextButtons: List[ContextButtonMapping])

case class Activities(activities: List[Activity], error: Option[String])

case class ActivitiesMap(activities: Map[NonEmptyString, Activity], error: Option[String])
