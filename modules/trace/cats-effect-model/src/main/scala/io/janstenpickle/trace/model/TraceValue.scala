package io.janstenpickle.trace.model

sealed trait TraceValue extends Product with Serializable {
  def value: Any
  override def toString: String = value.toString
}

object TraceValue {

  case class StringValue(value: String) extends TraceValue
  case class BooleanValue(value: Boolean) extends TraceValue
  case class NumberValue(value: Double) extends TraceValue

  implicit def stringToTraceValue(value: String): TraceValue = StringValue(value)
  implicit def boolToTraceValue(value: Boolean): TraceValue = BooleanValue(value)
  implicit def intToTraceValue(value: Int): TraceValue = NumberValue(value)

}
