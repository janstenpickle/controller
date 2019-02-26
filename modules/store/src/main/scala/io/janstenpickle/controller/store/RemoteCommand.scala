package io.janstenpickle.controller.store

import eu.timepit.refined.types.string.NonEmptyString

case class RemoteCommand(remote: NonEmptyString, device: NonEmptyString, name: NonEmptyString)
