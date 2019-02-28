package io.janstenpickle.controller.configsource

import cats.Apply
import cats.syntax.apply._
import io.janstenpickle.controller.model.Buttons

trait ButtonConfigSource[F[_]] {
  def getCommonButtons: F[Buttons]
}

object ButtonConfigSource {
  def combined[F[_]: Apply](x: ButtonConfigSource[F], y: ButtonConfigSource[F]): ButtonConfigSource[F] =
    new ButtonConfigSource[F] {
      override def getCommonButtons: F[Buttons] = x.getCommonButtons.map2(y.getCommonButtons) { (x0, y0) =>
        Buttons(x0.buttons ++ y0.buttons, x0.errors ++ y0.errors)
      }
    }
}
