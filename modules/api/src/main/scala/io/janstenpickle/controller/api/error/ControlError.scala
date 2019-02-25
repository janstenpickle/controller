package io.janstenpickle.controller.api.error

import scala.util.control.NoStackTrace

sealed trait ControlError extends NoStackTrace {
  def message: String
  override def getMessage: String = message
}

object ControlError {
  case class Missing(message: String) extends ControlError
  case class Internal(message: String) extends ControlError
}
