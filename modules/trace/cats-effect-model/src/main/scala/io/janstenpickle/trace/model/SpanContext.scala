package io.janstenpickle.trace.model

import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Defer, MonadError}

case class SpanContext(
  traceId: TraceId,
  spanId: SpanId,
  parent: Option[SpanId],
  traceFlags: TraceFlags,
  traceState: TraceState,
  isRemote: Boolean
) {
  def setIsSampled(): SpanContext = copy(traceFlags = traceFlags.copy(sampled = true))
}

object SpanContext {
  def root[F[_]: Defer: MonadError[*[_], Throwable]]: F[SpanContext] =
    for {
      traceId <- TraceId[F]
      spanId <- SpanId[F]
    } yield SpanContext(traceId, spanId, None, TraceFlags(false), TraceState.empty, isRemote = false)

  def child[F[_]: Defer: MonadError[*[_], Throwable]](parent: SpanContext, isRemote: Boolean = false): F[SpanContext] =
    SpanId[F].map { spanId =>
      SpanContext(parent.traceId, spanId, Some(parent.spanId), parent.traceFlags, parent.traceState, isRemote)
    }
}
