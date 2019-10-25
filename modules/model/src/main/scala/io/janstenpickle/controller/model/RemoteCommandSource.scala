package io.janstenpickle.controller.model

import eu.timepit.refined.types.string.NonEmptyString

case class RemoteCommandSource(name: NonEmptyString, `type`: NonEmptyString)
