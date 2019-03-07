package io.janstenpickle.controller.model

import eu.timepit.refined.types.string.NonEmptyString

case class Rooms(rooms: List[NonEmptyString], errors: List[String])
