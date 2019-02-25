package io.janstenpickle.controller.model

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString

case class Remote(name: NonEmptyString, activities: NonEmptyList[NonEmptyString], buttons: NonEmptyList[Button])
