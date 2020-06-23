package io.janstenpickle.trace.avro

import io.janstenpickle.trace.model.{
  CompletedSpan,
  SpanContext,
  SpanId,
  SpanKind,
  SpanStatus,
  TraceFlags,
  TraceId,
  TraceState,
  TraceValue
}
import vulcan.{AvroError, Codec}
import vulcan.generic._
import cats.syntax.traverse._
import cats.instances.list._

object AvroInstances {
  implicit val spanIdCodec: Codec[SpanId] =
    Codec.bytes.imapError(SpanId(_).toRight(AvroError("Invalid Span ID")))(_.value)

  implicit val traceIdCodec: Codec[TraceId] =
    Codec.bytes.imapError(TraceId(_).toRight(AvroError("Invalid Trace ID")))(_.value)

  implicit val traceStateKeyCodec: Codec[TraceState.Key] =
    Codec.string.imapError(TraceState.Key(_).toRight(AvroError("Invalid trace state key")))(_.k)

  implicit val traceStateValueCodec: Codec[TraceState.Value] =
    Codec.string.imapError(TraceState.Value(_).toRight(AvroError("Invalid trace state value")))(_.v)

  implicit val traceStateCodec: Codec[TraceState] = Codec
    .map[TraceState.Value]
    .imap(_.flatMap { case (k, v) => TraceState.Key(k).map(_ -> v) })(_.map {
      case (k, v) => k.k -> v
    })
    .imapError[TraceState](TraceState(_).toRight(AvroError("Invalid trace state size")))(_.values)

  implicit val traceFlagsCodec: Codec[TraceFlags] = Codec.boolean.imap(TraceFlags)(_.sampled)

  implicit val spanContextCodec: Codec[SpanContext] = Codec.derive

  implicit val traceValueCodec: Codec[TraceValue] = Codec.derive[TraceValue]

  implicit val attributesCodec: Codec[Map[String, TraceValue]] = Codec.map[TraceValue]

  implicit val spanKindCodec: Codec[SpanKind] = Codec.derive[SpanKind]

  implicit val spanStatusCodec: Codec[SpanStatus] = Codec.derive[SpanStatus]

  implicit val completedSpanCodec: Codec[CompletedSpan] = Codec.derive
}
