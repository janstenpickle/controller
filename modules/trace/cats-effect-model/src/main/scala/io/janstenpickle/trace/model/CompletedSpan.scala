package io.janstenpickle.trace.model

case class CompletedSpan(
  context: SpanContext,
  name: String,
  kind: SpanKind,
  start: Long,
  end: Long,
  attributes: Map[String, TraceValue],
  status: SpanStatus
)
