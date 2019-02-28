package io.janstenpickle.controller.configsource

import cats.Apply
import cats.syntax.apply._
import io.janstenpickle.controller.model.Activities

trait ActivityConfigSource[F[_]] {
  def getActivities: F[Activities]
}

object ActivityConfigSource {
  def combined[F[_]: Apply](x: ActivityConfigSource[F], y: ActivityConfigSource[F]): ActivityConfigSource[F] =
    new ActivityConfigSource[F] {
      override def getActivities: F[Activities] = x.getActivities.map2(y.getActivities) { (x0, y0) =>
        Activities(x0.activities ++ y0.activities, x0.errors ++ y0.errors)
      }
    }
}
