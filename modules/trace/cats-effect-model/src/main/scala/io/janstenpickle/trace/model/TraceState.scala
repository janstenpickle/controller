package io.janstenpickle.trace.model

import cats.Show
import io.janstenpickle.trace.model.TraceState.{Key, Value}

case class TraceState private (values: Map[Key, Value]) extends AnyVal

object TraceState {
  def empty: TraceState = new TraceState(Map.empty)
  def apply(values: Map[Key, Value]): Option[TraceState] = if (values.size > 32) None else Some(new TraceState(values))

  case class Key private (k: String) extends AnyVal
  object Key {
    private val regex = "^([0-9a-z_\\-*/]+)$".r
    def apply(k: String): Option[Key] = if (regex.matches(k)) Some(new Key(k)) else None

    implicit val show: Show[Key] = Show.show(_.k)
  }

  case class Value private (v: String) extends AnyVal {
    override def toString: String = v
  }
  object Value {
    private val regex = "((,|=|\\s)+)".r
    def apply(v: String): Option[Value] = if (v.length > 256 && regex.matches(v)) None else Some(new Value(v))

    implicit val show: Show[Value] = Show.show(_.v)
  }
}
