package io.janstenpickle.controller.model

import cats.Eq
import cats.instances.string._

case class CommandPayload(hexValue: String) extends AnyVal

object CommandPayload {
  implicit val commandPayloadEq: Eq[CommandPayload] = Eq.by(_.hexValue)
}
