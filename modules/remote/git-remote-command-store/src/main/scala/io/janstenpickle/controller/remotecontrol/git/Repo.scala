package io.janstenpickle.controller.remotecontrol.git

import eu.timepit.refined.types.string.NonEmptyString

case class Repo(name: NonEmptyString, owner: NonEmptyString, repo: NonEmptyString, ref: Option[NonEmptyString] = None)
