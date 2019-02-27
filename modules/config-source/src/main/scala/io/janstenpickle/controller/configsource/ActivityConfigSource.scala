package io.janstenpickle.controller.configsource

import io.janstenpickle.controller.model.{Activities, ActivitiesMap}

trait ActivityConfigSource[F[_]] {
  def getActivities: F[Activities]
}
