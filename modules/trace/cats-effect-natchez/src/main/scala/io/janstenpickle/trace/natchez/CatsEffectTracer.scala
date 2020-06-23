package io.janstenpickle.trace.natchez

import _root_.natchez.{EntryPoint, Kernel, Span}
import cats.effect.{Clock, Resource, Sync}
import io.janstenpickle.trace.model.SpanKind
import io.janstenpickle.trace.{RefSpan, SpanCompleter, ToHeaders}

object CatsEffectTracer {
  def entryPoint[F[_]: Sync: Clock: ToHeaders](completer: SpanCompleter[F]): EntryPoint[F] = new EntryPoint[F] {
    override def root(name: String): Resource[F, Span[F]] =
      CatsEffectSpan.resource(RefSpan.root(name, SpanKind.Internal, completer))

    override def continue(name: String, kernel: Kernel): Resource[F, Span[F]] =
      CatsEffectSpan.resource(ToHeaders[F].toContext(kernel.toHeaders) match {
        case None => RefSpan.root(name, SpanKind.Server, completer)
        case Some(parent) => RefSpan.child(name, parent, SpanKind.Server, completer)
      })

    override def continueOrElseRoot(name: String, kernel: Kernel): Resource[F, Span[F]] = continue(name, kernel)
  }
}
