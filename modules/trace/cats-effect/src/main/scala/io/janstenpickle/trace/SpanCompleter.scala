package io.janstenpickle.trace

import io.janstenpickle.trace.model.CompletedSpan

trait SpanCompleter[F[_]] {
  def complete(span: CompletedSpan): F[Unit]
}
