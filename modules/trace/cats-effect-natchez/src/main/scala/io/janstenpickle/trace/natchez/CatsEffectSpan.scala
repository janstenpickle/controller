package io.janstenpickle.trace.natchez

import _root_.natchez.{Kernel, Span, TraceValue => V}
import cats.Applicative
import cats.effect.{Clock, ExitCase, Resource, Sync}
import cats.syntax.flatMap._
import io.janstenpickle.trace.model.TraceValue.{BooleanValue, NumberValue, StringValue}
import io.janstenpickle.trace.model.{SpanKind, SpanStatus, TraceValue}
import io.janstenpickle.trace.{RefSpan, ToHeaders}

final case class CatsEffectSpan[F[_]: Sync: Clock: ToHeaders](span: RefSpan[F]) extends Span[F] {
  override def put(fields: (String, V)*): F[Unit] =
    span.putAll(fields.map[(String, TraceValue)] {
      case (k, V.StringValue(v)) => k -> StringValue(v)
      case (k, V.NumberValue(v)) => k -> NumberValue(v.doubleValue())
      case (k, V.BooleanValue(v)) => k -> BooleanValue(v)
    }: _*)

  override def kernel: F[Kernel] = Applicative[F].pure(Kernel(ToHeaders[F].fromContext(span.context)))

  override def span(name: String): Resource[F, Span[F]] =
    CatsEffectSpan.resource(RefSpan.child(name, span.context, SpanKind.Internal, span.completer))
}

object CatsEffectSpan {
  def resource[F[_]: Sync: Clock: ToHeaders](span: F[RefSpan[F]]): Resource[F, Span[F]] =
    Resource
      .makeCase(span) {
        case (span, ExitCase.Completed) => span.end(SpanStatus.Ok)
        case (span, ExitCase.Canceled) => span.end(SpanStatus.Cancelled)
        case (span, ExitCase.Error(th)) =>
          span.putAll("error" -> true, "error.message" -> th.getMessage) >> span.end(SpanStatus.Internal)
      }
      .map(CatsEffectSpan(_))
}
