package io.janstenpickle.controller.deconz.model

import cats.Eq
import cats.instances.string._

case class ButtonMappingKey(id: String, action: ButtonAction)

object ButtonMappingKey {
  implicit val buttonMappingKeyEq: Eq[ButtonMappingKey] = cats.derived.semi.eq
}
