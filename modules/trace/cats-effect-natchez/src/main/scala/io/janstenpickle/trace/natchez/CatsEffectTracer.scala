package io.janstenpickle.trace.natchez

import _root_.natchez.{EntryPoint, Kernel, Span}
import cats.effect.{Clock, Resource, Sync}
import io.janstenpickle.trace.model.SpanKind
import io.janstenpickle.trace.{SpanCompleter, SpanSampler, ToHeaders}

object CatsEffectTracer {
  def entryPoint[F[_]: Sync: Clock: ToHeaders](sampler: SpanSampler[F], completer: SpanCompleter[F]): EntryPoint[F] =
    new EntryPoint[F] {
      override def root(name: String): Resource[F, Span[F]] =
        CatsEffectSpan
          .resource(io.janstenpickle.trace.Span.root(name, SpanKind.Internal, sampler, completer), sampler, completer)

      override def continue(name: String, kernel: Kernel): Resource[F, Span[F]] =
        CatsEffectSpan.resource(ToHeaders[F].toContext(kernel.toHeaders) match {
          case None => io.janstenpickle.trace.Span.root(name, SpanKind.Server, sampler, completer)
          case Some(parent) => io.janstenpickle.trace.Span.child(name, parent, SpanKind.Server, sampler, completer)
        }, sampler, completer)

      override def continueOrElseRoot(name: String, kernel: Kernel): Resource[F, Span[F]] = continue(name, kernel)
    }
}
