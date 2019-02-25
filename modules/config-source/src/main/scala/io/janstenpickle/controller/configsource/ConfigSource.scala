package io.janstenpickle.controller.configsource

import eu.timepit.refined.types.string.NonEmptyString
import io.janstenpickle.controller.model.{Activity, Button, Remote}

trait ConfigSource[F[_]] {
  def getActivities: F[Map[NonEmptyString, Activity]]
  def getRemotes: F[List[Remote]]
  def getCommonButtons: F[List[Button]]
}
