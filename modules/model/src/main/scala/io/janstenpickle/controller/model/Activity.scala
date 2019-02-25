package io.janstenpickle.controller.model

import eu.timepit.refined.types.string.NonEmptyString

case class Activity(name: NonEmptyString, label: NonEmptyString)
