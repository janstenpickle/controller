package io.janstenpickle.deconz.config

import cats.Eq
import io.janstenpickle.deconz.model.ButtonAction

case class ActionMapping(button: ButtonAction, controller: ControllerAction)

object ActionMapping {
  implicit val actionMappingEq: Eq[ActionMapping] = cats.derived.semi.eq
}
