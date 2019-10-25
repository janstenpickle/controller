package io.janstenpickle.controller.remotecontrol

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.RemoteCommandSource

package object git {
  def commandSource(repo: Repo): Option[RemoteCommandSource] =
    Some(RemoteCommandSource(repo.name, NonEmptyString("github")))
}
