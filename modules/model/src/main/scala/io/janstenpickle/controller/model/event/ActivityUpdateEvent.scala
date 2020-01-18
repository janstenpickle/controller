package io.janstenpickle.controller.model.event

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.Room

case class ActivityUpdateEvent(room: Room, name: NonEmptyString, error: Option[Throwable] = None)
