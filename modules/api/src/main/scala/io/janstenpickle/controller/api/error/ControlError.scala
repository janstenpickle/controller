package io.janstenpickle.controller.api.error

import cats.kernel.Semigroup

import scala.util.control.NoStackTrace

sealed trait ControlError extends NoStackTrace {
  def message: String
  override def getMessage: String = message
}

object ControlError {
  sealed trait SimpleControlError extends ControlError
  case class Missing(message: String) extends SimpleControlError
  case class Internal(message: String) extends SimpleControlError
  case class InvalidInput(message: String) extends SimpleControlError
  case class Combined(first: ControlError, second: ControlError) extends ControlError {
    lazy val toList: List[SimpleControlError] = (first, second) match {
      case (f: Combined, s: Combined) => f.toList ++ s.toList
      case (f: Combined, s: SimpleControlError) => s :: f.toList
      case (f: SimpleControlError, s: Combined) => f :: s.toList
      case (f: SimpleControlError, s: SimpleControlError) => List(f, s)
    }
    lazy val isSevere: Boolean = toList.collectFirst { case _ @Internal(_) => () }.isDefined
    override lazy val message: String = toList.map(_.message).mkString(",")
  }

  implicit val controlErrorSemigroup: Semigroup[ControlError] = new Semigroup[ControlError] {
    override def combine(x: ControlError, y: ControlError): ControlError = Combined(x, y)
  }
}
